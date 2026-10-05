package org.saket.eventbooking.session.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.exception.BadRequestException;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.event.entity.Event;
import org.saket.eventbooking.event.enums.EventStatus;
import org.saket.eventbooking.common.cache.EvictsEventListings;
import org.saket.eventbooking.event.service.EventService;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.location.entity.Hall;
import org.saket.eventbooking.location.entity.Location;
import org.saket.eventbooking.location.entity.Seat;
import org.saket.eventbooking.location.service.HallService;
import org.saket.eventbooking.location.service.LocationService;
import org.saket.eventbooking.session.dto.SeatMapResponse;
import org.saket.eventbooking.session.dto.SessionRequest;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.dto.SessionUpdateRequest;
import org.saket.eventbooking.session.dto.TicketTierRequest;
import org.saket.eventbooking.session.dto.TicketTierResponse;
import org.saket.eventbooking.session.entity.Session;
import org.saket.eventbooking.session.entity.SessionSeat;
import org.saket.eventbooking.session.entity.TicketTier;
import org.saket.eventbooking.session.enums.PricingMode;
import org.saket.eventbooking.session.enums.SeatingType;
import org.saket.eventbooking.session.enums.SessionSeatStatus;
import org.saket.eventbooking.session.enums.SessionStatus;
import org.saket.eventbooking.session.repository.SessionRepository;
import org.saket.eventbooking.session.repository.SessionSeatRepository;
import org.saket.eventbooking.session.repository.TicketTierRepository;
import org.saket.eventbooking.event.event.EventCancelledEvent;
import org.saket.eventbooking.session.event.SessionCancelledEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sessions are where the seating model is decided. A session is exactly one of:
 * assigned seating (a hall's seats copied into per-session {@link SessionSeat} rows with frozen prices),
 * flat general admission (one capacity counter on the session), or tiered general admission
 * ({@link TicketTier} rows, each with its own capacity). The checks below keep the three from mixing.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;
    private final SessionSeatRepository sessionSeatRepository;
    private final TicketTierRepository ticketTierRepository;
    private final EventService eventService;
    private final LocationService locationService;
    private final HallService hallService;
    private final SeatPriceCalculator seatPriceCalculator;
    private final ApplicationEventPublisher events;

    // ---------------------------------------------------------------- creation

    @EvictsEventListings
    @Transactional
    public SessionResponse create(UUID eventId, SessionRequest request) {
        Event event = eventService.getEntity(eventId);
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new BadRequestException("Can't add sessions to a cancelled event");
        }
        Location location = locationService.getEntity(request.locationId());
        requireEndAfterStart(request.startTime(), request.endTime());

        Session session = new Session();
        session.setEvent(event);
        session.setLocation(location);
        session.setStartTime(request.startTime());
        session.setEndTime(request.endTime());
        session.setSeatingType(request.seatingType());
        session.setStatus(SessionStatus.SCHEDULED);

        List<TicketTierRequest> tiers = request.tiers() == null ? List.of() : request.tiers();
        List<Seat> hallSeats = List.of();

        if (request.seatingType() == SeatingType.ASSIGNED_SEATING) {
            hallSeats = configureAssignedSeating(session, request, tiers, location);
        } else {
            configureGeneralAdmission(session, request, tiers);
        }
        sessionRepository.save(session);

        if (!hallSeats.isEmpty()) {
            generateSessionSeats(session, hallSeats);
        }
        for (TicketTierRequest tier : tiers) {
            saveNewTier(session, tier);
        }
        return toResponse(session);
    }

    private List<Seat> configureAssignedSeating(Session session, SessionRequest request,
                                                List<TicketTierRequest> tiers, Location location) {
        if (request.hallId() == null) {
            throw new BadRequestException("hallId is required for assigned seating");
        }
        if (request.pricingMode() != null) {
            throw new BadRequestException("pricingMode must be empty for assigned seating (prices come from seat types)");
        }
        if (request.availableCapacity() != null) {
            throw new BadRequestException("availableCapacity applies only to flat general admission");
        }
        if (!tiers.isEmpty()) {
            throw new BadRequestException("tiers apply only to tiered general admission");
        }
        requireBasePrice(request);

        Hall hall = hallService.getEntity(request.hallId());
        if (!hall.getLocation().getId().equals(location.getId())) {
            throw new BadRequestException("Hall does not belong to the given location");
        }
        List<Seat> seats = hallService.getSeats(hall.getId());
        if (seats.isEmpty()) {
            throw new BadRequestException("Hall has no seats; define its seat layout first");
        }

        session.setHall(hall);
        session.setPricingMode(null);
        session.setAvailableCapacity(null);
        session.setBasePrice(request.basePrice());
        return seats;
    }

    private void configureGeneralAdmission(Session session, SessionRequest request, List<TicketTierRequest> tiers) {
        if (request.hallId() != null) {
            throw new BadRequestException("hallId applies only to assigned seating");
        }
        if (request.pricingMode() == null) {
            throw new BadRequestException("pricingMode (FLAT or TIERED) is required for general admission");
        }
        session.setHall(null);
        session.setPricingMode(request.pricingMode());

        if (request.pricingMode() == PricingMode.FLAT) {
            if (request.availableCapacity() == null) {
                throw new BadRequestException("availableCapacity is required for flat general admission");
            }
            if (!tiers.isEmpty()) {
                throw new BadRequestException("tiers apply only to tiered general admission");
            }
            requireBasePrice(request);
            session.setAvailableCapacity(request.availableCapacity());
            session.setBasePrice(request.basePrice());
        } else {
            if (tiers.isEmpty()) {
                throw new BadRequestException("Tiered general admission needs at least one ticket tier");
            }
            if (request.availableCapacity() != null) {
                throw new BadRequestException("availableCapacity must be empty for tiered sessions (capacity lives on tiers)");
            }
            requireUniqueTierNames(tiers.stream().map(TicketTierRequest::name).toList());
            session.setAvailableCapacity(null);
            // basePrice is NOT NULL; for tiered sessions it mirrors the cheapest tier ("from ₹X").
            session.setBasePrice(tiers.stream().map(TicketTierRequest::price).min(Comparator.naturalOrder()).orElseThrow());
        }
    }

    /** Copies every hall seat into a per-session row, pricing it from its seat type right now. */
    private void generateSessionSeats(Session session, List<Seat> hallSeats) {
        List<SessionSeat> sessionSeats = new ArrayList<>(hallSeats.size());
        for (Seat seat : hallSeats) {
            SessionSeat sessionSeat = new SessionSeat();
            sessionSeat.setSession(session);
            sessionSeat.setSeat(seat);
            sessionSeat.setStatus(SessionSeatStatus.AVAILABLE);
            sessionSeat.setPrice(seatPriceCalculator.priceFor(seat.getSeatType(), session.getBasePrice()));
            sessionSeats.add(sessionSeat);
        }
        sessionSeatRepository.saveAll(sessionSeats);
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<SessionResponse> listForEvent(UUID eventId) {
        eventService.getEntity(eventId);
        return toResponses(sessionRepository.findByEventIdOrderByStartTime(eventId));
    }

    /** Public: future, scheduled sessions of a published event. */
    @Transactional(readOnly = true)
    public List<SessionResponse> listUpcomingForPublishedEvent(UUID eventId) {
        eventService.getPublishedEntity(eventId);
        return toResponses(sessionRepository.findByEventIdAndStatusAndStartTimeAfterOrderByStartTime(
                eventId, SessionStatus.SCHEDULED, Instant.now()));
    }

    @Transactional(readOnly = true)
    public SessionResponse get(UUID id) {
        return toResponse(getWithDetails(id));
    }

    /** Public: sessions of unpublished events don't exist. */
    @Transactional(readOnly = true)
    public SessionResponse getPublic(UUID id) {
        return toResponse(getPublicWithDetails(id));
    }

    @Transactional(readOnly = true)
    public SeatMapResponse seatMap(UUID id) {
        return toSeatMap(getWithDetails(id));
    }

    @Transactional(readOnly = true)
    public SeatMapResponse publicSeatMap(UUID id) {
        return toSeatMap(getPublicWithDetails(id));
    }

    // ---------------------------------------------------------------- updates

    /**
     * Updates timing and status. Moving to CANCELLED cascades (see {@link #cancel}); a cancelled session
     * can't be reopened and a completed one can't be cancelled.
     */
    @EvictsEventListings
    @Transactional
    public SessionResponse update(UUID id, SessionUpdateRequest request) {
        Session session = getWithDetails(id);
        requireEndAfterStart(request.startTime(), request.endTime());
        if (session.getStatus() == SessionStatus.CANCELLED && request.status() != SessionStatus.CANCELLED) {
            throw new BadRequestException("A cancelled session can't be reopened");
        }
        if (session.getStatus() == SessionStatus.COMPLETED && request.status() == SessionStatus.CANCELLED) {
            throw new BadRequestException("A completed session can't be cancelled");
        }
        session.setStartTime(request.startTime());
        session.setEndTime(request.endTime());
        if (request.status() == SessionStatus.CANCELLED) {
            cancel(session);
        } else {
            session.setStatus(request.status());
        }
        return toResponse(session);
    }

    /** An event was cancelled: cancel its sessions that haven't happened yet (same transaction). */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onEventCancelled(EventCancelledEvent event) {
        for (Session session : sessionRepository.findByEventIdOrderByStartTime(event.eventId())) {
            if (session.getStatus() == SessionStatus.SCHEDULED) {
                cancel(session);
            }
        }
    }

    /**
     * SCHEDULED -> CANCELLED and announce it. Listeners (the booking domain) run synchronously in this
     * transaction: no new holds are possible (holds require SCHEDULED) and existing bookings are ended.
     */
    private void cancel(Session session) {
        if (session.getStatus() == SessionStatus.CANCELLED) {
            return;
        }
        session.setStatus(SessionStatus.CANCELLED);
        events.publishEvent(new SessionCancelledEvent(session.getId()));
    }

    /** Removes the session with its seats and tiers. 409 once bookings reference it. */
    @EvictsEventListings
    @Transactional
    public void delete(UUID id) {
        Session session = getEntity(id);
        try {
            sessionSeatRepository.deleteBySessionId(id);
            ticketTierRepository.deleteBySessionId(id);
            sessionRepository.delete(session);
            sessionRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Session has bookings and can't be deleted; cancel it instead");
        }
    }

    @EvictsEventListings
    @Transactional
    public SessionResponse addTier(UUID sessionId, TicketTierRequest request) {
        Session session = getWithDetails(sessionId);
        requireTiered(session);
        List<String> names = new ArrayList<>(ticketTierRepository.findBySessionIdOrderByPriceAscNameAsc(sessionId)
                .stream().map(TicketTier::getName).toList());
        names.add(request.name());
        requireUniqueTierNames(names);

        saveNewTier(session, request);
        syncTieredBasePrice(session);
        return toResponse(session);
    }

    /**
     * Changing total capacity shifts available capacity by the same delta, so tickets already sold
     * stay sold. Refused if that would leave negative availability.
     */
    @EvictsEventListings
    @Transactional
    public SessionResponse updateTier(UUID tierId, TicketTierRequest request) {
        TicketTier tier = ticketTierRepository.findByIdForUpdate(tierId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket tier", tierId));
        Session session = tier.getSession();

        List<String> names = new ArrayList<>(ticketTierRepository.findBySessionIdOrderByPriceAscNameAsc(session.getId())
                .stream().filter(t -> !t.getId().equals(tierId)).map(TicketTier::getName).toList());
        names.add(request.name());
        requireUniqueTierNames(names);

        int sold = tier.getTotalCapacity() - tier.getAvailableCapacity();
        if (request.totalCapacity() < sold) {
            throw new ConflictException("Capacity can't go below the " + sold + " tickets already sold for this tier");
        }
        tier.setName(request.name().trim());
        tier.setPrice(request.price());
        tier.setAvailableCapacity(request.totalCapacity() - sold);
        tier.setTotalCapacity(request.totalCapacity());

        syncTieredBasePrice(session);
        return toResponse(getWithDetails(session.getId()));
    }

    @EvictsEventListings
    @Transactional
    public SessionResponse deleteTier(UUID tierId) {
        TicketTier tier = ticketTierRepository.findByIdForUpdate(tierId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket tier", tierId));
        Session session = tier.getSession();
        if (ticketTierRepository.findBySessionIdOrderByPriceAscNameAsc(session.getId()).size() <= 1) {
            throw new BadRequestException("A tiered session needs at least one tier");
        }
        try {
            ticketTierRepository.delete(tier);
            ticketTierRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Tier has bookings and can't be deleted");
        }
        syncTieredBasePrice(session);
        return toResponse(getWithDetails(session.getId()));
    }

    // ---------------------------------------------------------------- helpers

    private Session getEntity(UUID id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Session", id));
    }

    private Session getWithDetails(UUID id) {
        return sessionRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Session", id));
    }

    private Session getPublicWithDetails(UUID id) {
        return sessionRepository.findWithDetailsById(id)
                .filter(s -> s.getEvent().getStatus() == EventStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("Session", id));
    }

    private void saveNewTier(Session session, TicketTierRequest request) {
        TicketTier tier = new TicketTier();
        tier.setSession(session);
        tier.setName(request.name().trim());
        tier.setPrice(request.price());
        tier.setTotalCapacity(request.totalCapacity());
        tier.setAvailableCapacity(request.totalCapacity());
        ticketTierRepository.save(tier);
    }

    private void syncTieredBasePrice(Session session) {
        ticketTierRepository.findBySessionIdOrderByPriceAscNameAsc(session.getId()).stream()
                .map(TicketTier::getPrice)
                .min(Comparator.naturalOrder())
                .ifPresent(session::setBasePrice);
    }

    private static void requireTiered(Session session) {
        if (session.getSeatingType() != SeatingType.GENERAL_ADMISSION || session.getPricingMode() != PricingMode.TIERED) {
            throw new BadRequestException("Ticket tiers only apply to tiered general-admission sessions");
        }
    }

    private static void requireBasePrice(SessionRequest request) {
        if (request.basePrice() == null) {
            throw new BadRequestException("basePrice is required");
        }
    }

    private static void requireEndAfterStart(Instant start, Instant end) {
        if (!end.isAfter(start)) {
            throw new BadRequestException("endTime must be after startTime");
        }
    }

    private static void requireUniqueTierNames(List<String> names) {
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!seen.add(name.trim().toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("Duplicate tier name: " + name.trim());
            }
        }
    }

    private SessionResponse toResponse(Session session) {
        return toResponses(List.of(session)).getFirst();
    }

    /** Maps sessions with two batched queries (available-seat counts and tiers), not one per session. */
    private List<SessionResponse> toResponses(List<Session> sessions) {
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = sessions.stream().map(Session::getId).toList();

        Map<UUID, Integer> availableSeats = new HashMap<>();
        List<UUID> assignedIds = sessions.stream()
                .filter(s -> s.getSeatingType() == SeatingType.ASSIGNED_SEATING)
                .map(Session::getId).toList();
        if (!assignedIds.isEmpty()) {
            for (Object[] row : sessionSeatRepository.countBySessionIdsAndStatus(assignedIds, SessionSeatStatus.AVAILABLE)) {
                availableSeats.put((UUID) row[0], ((Number) row[1]).intValue());
            }
        }

        Map<UUID, List<TicketTier>> tiersBySession = ticketTierRepository.findBySessionIdIn(ids).stream()
                .sorted(Comparator.comparing(TicketTier::getPrice).thenComparing(TicketTier::getName))
                .collect(Collectors.groupingBy(t -> t.getSession().getId()));

        return sessions.stream().map(session -> {
            List<TicketTierResponse> tiers = tiersBySession.getOrDefault(session.getId(), List.of()).stream()
                    .map(TicketTierResponse::from).toList();
            int ticketsAvailable = switch (session.getSeatingType()) {
                case ASSIGNED_SEATING -> availableSeats.getOrDefault(session.getId(), 0);
                case GENERAL_ADMISSION -> session.getPricingMode() == PricingMode.TIERED
                        ? tiers.stream().mapToInt(TicketTierResponse::availableCapacity).sum()
                        : session.getAvailableCapacity();
            };
            Hall hall = session.getHall();
            return new SessionResponse(
                    session.getId(),
                    session.getEvent().getId(),
                    LocationResponse.from(session.getLocation()),
                    hall != null ? hall.getId() : null,
                    hall != null ? hall.getName() : null,
                    session.getStartTime(),
                    session.getEndTime(),
                    session.getSeatingType(),
                    session.getPricingMode(),
                    session.getStatus(),
                    session.getBasePrice(),
                    ticketsAvailable,
                    tiers);
        }).toList();
    }

    private SeatMapResponse toSeatMap(Session session) {
        if (session.getSeatingType() != SeatingType.ASSIGNED_SEATING) {
            throw new BadRequestException("General-admission sessions don't have a seat map");
        }
        Comparator<SessionSeat> order = Comparator.comparing(SessionSeat::getSeat, HallService.SEAT_ORDER);
        List<SeatMapResponse.SeatEntry> seats = sessionSeatRepository.findBySessionIdWithSeat(session.getId()).stream()
                .sorted(order)
                .map(ss -> new SeatMapResponse.SeatEntry(ss.getId(), ss.getSeat().getRowLabel(),
                        ss.getSeat().getSeatNumber(), ss.getSeat().getSeatType(), ss.getStatus(), ss.getPrice()))
                .toList();
        return new SeatMapResponse(session.getId(), session.getHall().getId(), session.getHall().getName(), seats);
    }
}
