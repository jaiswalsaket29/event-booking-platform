package org.saket.eventbooking.booking.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.booking.config.BookingProperties;
import jakarta.persistence.criteria.Predicate;
import org.saket.eventbooking.booking.dto.AdminBookingFilter;
import org.saket.eventbooking.booking.dto.AdminBookingResponse;
import org.saket.eventbooking.booking.dto.BookingResponse;
import org.saket.eventbooking.booking.dto.CreateBookingRequest;
import org.saket.eventbooking.booking.entity.Booking;
import org.saket.eventbooking.booking.entity.BookingSeat;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.booking.hold.BookingHoldStore;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.booking.repository.BookingSeatRepository;
import org.saket.eventbooking.common.dto.PageResponse;
import org.saket.eventbooking.common.exception.BadRequestException;
import org.saket.eventbooking.common.exception.ForbiddenException;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.location.service.HallService;
import org.saket.eventbooking.payment.dto.PaymentResponse;
import org.saket.eventbooking.payment.service.PaymentQueryService;
import org.saket.eventbooking.session.entity.Session;
import org.saket.eventbooking.session.entity.SessionSeat;
import org.saket.eventbooking.session.entity.TicketTier;
import org.saket.eventbooking.session.service.SessionInventoryService;
import org.saket.eventbooking.session.service.seating.Hold;
import org.saket.eventbooking.session.service.seating.HoldRequest;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.service.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final SessionInventoryService sessionInventoryService;
    private final UserService userService;
    private final BookingHoldStore holdStore;
    private final BookingProperties properties;
    private final PaymentQueryService paymentQueryService;

    /**
     * Starts a checkout: holds the inventory under row locks, records a PENDING booking, and starts the
     * hold timer in Redis, all in one transaction. If Redis is unreachable the placement throws and
     * everything rolls back, so there is never a PENDING booking without a timer. (A key left behind by
     * a rolled-back transaction just expires and finds no booking.)
     */
    @Transactional
    public BookingResponse create(UUID userId, CreateBookingRequest request) {
        User user = userService.getById(userId);
        // Checked against the database, not the token: the JWT claim is stale until the next refresh.
        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new ForbiddenException("Verify your email address before booking tickets");
        }
        int requested = request.sessionSeatIds() != null && !request.sessionSeatIds().isEmpty()
                ? request.sessionSeatIds().size()
                : request.quantity() == null ? 0 : request.quantity();
        if (requested > properties.maxTicketsPerBooking()) {
            throw new BadRequestException("At most " + properties.maxTicketsPerBooking() + " tickets per booking");
        }

        Hold hold = sessionInventoryService.hold(request.sessionId(),
                new HoldRequest(request.ticketTierId(), request.quantity(), request.sessionSeatIds()));

        Booking booking = new Booking();
        booking.setUser(user);
        booking.setSession(hold.session());
        booking.setTicketTier(hold.ticketTier());
        booking.transitionTo(BookingStatus.PENDING);
        booking.setQuantity(hold.quantity()); // GA only; assigned seating books specific seats
        booking.setTotalAmount(hold.totalAmount());
        booking.setCreatedAt(Instant.now());
        bookingRepository.save(booking);

        for (SessionSeat seat : hold.seats()) {
            BookingSeat bookingSeat = new BookingSeat();
            bookingSeat.setBooking(booking);
            bookingSeat.setSessionSeat(seat);
            bookingSeatRepository.save(bookingSeat);
        }

        holdStore.place(booking.getId(), properties.holdTtl());
        return toResponses(List.of(booking)).getFirst();
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> listMine(UUID userId, Pageable pageable) {
        Page<Booking> page = bookingRepository.findByUserId(userId, pageable);
        List<BookingResponse> content = toResponses(page.getContent());
        return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /**
     * Someone else's booking is reported as not found (404), not forbidden: booking ids must not be
     * confirmable by guessing, and the owner check is part of the query itself.
     */
    @Transactional(readOnly = true)
    public BookingResponse getMine(UUID userId, UUID bookingId) {
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
        return toResponses(List.of(booking)).getFirst();
    }

    /** Admin search across all users; newest first by default. */
    @Transactional(readOnly = true)
    public PageResponse<AdminBookingResponse> adminSearch(AdminBookingFilter filter, Pageable pageable) {
        Specification<Booking> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.sessionId() != null) {
                predicates.add(cb.equal(root.get("session").get("id"), filter.sessionId()));
            }
            if (filter.eventId() != null) {
                predicates.add(cb.equal(root.get("session").get("event").get("id"), filter.eventId()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), filter.to()));
            }
            if (filter.q() != null && !filter.q().isBlank()) {
                String q = filter.q().trim();
                predicates.add(q.toUpperCase(Locale.ROOT).startsWith("EVT-")
                        ? cb.equal(root.get("bookingReference"), q.toUpperCase(Locale.ROOT))
                        : cb.like(cb.lower(root.get("user").get("email")),
                        "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%", '\\'));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<Booking> page = bookingRepository.findAll(spec, pageable);
        List<AdminBookingResponse> content = toAdminResponses(page.getContent());
        return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AdminBookingResponse adminGet(UUID bookingId) {
        Booking booking = bookingRepository.findWithDetailsById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
        return toAdminResponses(List.of(booking)).getFirst();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private List<AdminBookingResponse> toAdminResponses(List<Booking> bookings) {
        List<BookingResponse> responses = toResponses(bookings);
        Map<UUID, List<PaymentResponse>> payments = paymentQueryService.listForBookings(
                bookings.stream().map(Booking::getId).toList());
        List<AdminBookingResponse> result = new ArrayList<>(bookings.size());
        for (int i = 0; i < bookings.size(); i++) {
            User user = bookings.get(i).getUser();
            result.add(new AdminBookingResponse(responses.get(i),
                    new AdminBookingResponse.Customer(user.getId(), user.getName(), user.getEmail()),
                    payments.getOrDefault(bookings.get(i).getId(), List.of())));
        }
        return result;
    }

    /** Maps bookings with one batched query for their seats. Must run inside a transaction. */
    List<BookingResponse> toResponses(List<Booking> bookings) {
        if (bookings.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<BookingSeat>> seatsByBooking = bookingSeatRepository
                .findByBookingIdsWithSeats(bookings.stream().map(Booking::getId).toList()).stream()
                .collect(Collectors.groupingBy(bs -> bs.getBooking().getId()));

        return bookings.stream().map(booking -> {
            List<BookingResponse.SeatInfo> seats = seatsByBooking.getOrDefault(booking.getId(), List.of()).stream()
                    .map(BookingSeat::getSessionSeat)
                    .sorted(Comparator.comparing(SessionSeat::getSeat, HallService.SEAT_ORDER))
                    .map(ss -> new BookingResponse.SeatInfo(ss.getId(), ss.getSeat().getRowLabel(),
                            ss.getSeat().getSeatNumber(), ss.getSeat().getSeatType(), ss.getPrice()))
                    .toList();
            Session session = booking.getSession();
            TicketTier tier = booking.getTicketTier();
            int ticketCount = booking.getQuantity() != null ? booking.getQuantity() : seats.size();
            Instant holdExpiresAt = booking.getStatus() == BookingStatus.PENDING
                    ? booking.getCreatedAt().plus(properties.holdTtl())
                    : null;
            return new BookingResponse(
                    booking.getId(),
                    booking.getStatus(),
                    booking.getBookingReference(),
                    session.getId(),
                    session.getEvent().getId(),
                    session.getEvent().getTitle(),
                    session.getStartTime(),
                    session.getLocation().getName(),
                    session.getLocation().getCity(),
                    session.getHall() != null ? session.getHall().getName() : null,
                    session.getSeatingType(),
                    tier != null ? new BookingResponse.TierInfo(tier.getId(), tier.getName(), tier.getPrice()) : null,
                    ticketCount,
                    seats,
                    booking.getTotalAmount(),
                    booking.getCreatedAt(),
                    holdExpiresAt);
        }).toList();
    }
}
