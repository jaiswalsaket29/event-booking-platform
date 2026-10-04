package org.saket.eventbooking.location.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.exception.BadRequestException;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.location.dto.HallDetailResponse;
import org.saket.eventbooking.location.dto.HallRequest;
import org.saket.eventbooking.location.dto.HallResponse;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.location.dto.SeatLayoutRequest;
import org.saket.eventbooking.location.dto.SeatResponse;
import org.saket.eventbooking.location.entity.Hall;
import org.saket.eventbooking.location.entity.Location;
import org.saket.eventbooking.location.entity.Seat;
import org.saket.eventbooking.location.enums.VenueType;
import org.saket.eventbooking.location.repository.HallRepository;
import org.saket.eventbooking.location.repository.SeatRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HallService {

    static final int MAX_ROWS = 100;
    static final int MAX_SEATS = 5000;

    /** Front-to-back, left-to-right. */
    public static final Comparator<Seat> SEAT_ORDER = Comparator
            .comparing(Seat::getRowLabel, RowLabels.ORDER)
            .thenComparingInt(Seat::getSeatNumber);

    private final HallRepository hallRepository;
    private final SeatRepository seatRepository;
    private final LocationService locationService;

    @Transactional(readOnly = true)
    public List<HallResponse> listByLocation(UUID locationId) {
        locationService.getEntity(locationId); // 404 for an unknown location rather than an empty list
        return hallRepository.findByLocationIdOrderByName(locationId).stream()
                .map(HallResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public HallDetailResponse getDetail(UUID id) {
        Hall hall = hallRepository.findWithLocationById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Hall", id));
        List<SeatResponse> seats = getSeats(id).stream().map(SeatResponse::from).toList();
        return new HallDetailResponse(hall.getId(), LocationResponse.from(hall.getLocation()), hall.getName(),
                hall.getTotalCapacity(), seats);
    }

    /** For other domains' services (e.g. session creation) that need the entity itself. */
    @Transactional(readOnly = true)
    public Hall getEntity(UUID id) {
        return hallRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Hall", id));
    }

    /** The hall's physical seats in display order. */
    @Transactional(readOnly = true)
    public List<Seat> getSeats(UUID hallId) {
        List<Seat> seats = new ArrayList<>(seatRepository.findByHallId(hallId));
        seats.sort(SEAT_ORDER);
        return seats;
    }

    @Transactional
    public HallResponse create(UUID locationId, HallRequest request) {
        Location location = locationService.getEntity(locationId);
        if (location.getVenueType() == VenueType.ONLINE) {
            throw new BadRequestException("Online locations can't have halls");
        }
        Hall hall = new Hall();
        hall.setLocation(location);
        hall.setName(request.name().trim());
        hall.setTotalCapacity(0); // derived from the seat layout
        return HallResponse.from(hallRepository.save(hall));
    }

    @Transactional
    public HallResponse update(UUID id, HallRequest request) {
        Hall hall = getEntity(id);
        hall.setName(request.name().trim());
        return HallResponse.from(hall);
    }

    /** Removes the hall and its seats. 409 if any session uses the hall. */
    @Transactional
    public void delete(UUID id) {
        Hall hall = getEntity(id);
        try {
            seatRepository.deleteByHallId(id);
            hallRepository.delete(hall);
            hallRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Hall is used by existing sessions and can't be deleted");
        }
    }

    /**
     * Replaces the hall's seat layout and recomputes its capacity. Seats are reference data shared by
     * every session in the hall, so this is refused (409) once any session has been created here.
     */
    @Transactional
    public HallDetailResponse replaceLayout(UUID id, SeatLayoutRequest request) {
        Hall hall = getEntity(id);

        int totalRows = request.blocks().stream().mapToInt(SeatLayoutRequest.Block::rows).sum();
        int totalSeats = request.blocks().stream().mapToInt(b -> b.rows() * b.seatsPerRow()).sum();
        if (totalRows > MAX_ROWS) {
            throw new BadRequestException("A hall can have at most " + MAX_ROWS + " rows");
        }
        if (totalSeats > MAX_SEATS) {
            throw new BadRequestException("A hall can have at most " + MAX_SEATS + " seats");
        }

        try {
            seatRepository.deleteByHallId(id);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Seat layout can't be changed: sessions already use this hall's seats");
        }

        List<Seat> seats = new ArrayList<>(totalSeats);
        int rowIndex = 0;
        for (SeatLayoutRequest.Block block : request.blocks()) {
            for (int r = 0; r < block.rows(); r++, rowIndex++) {
                String rowLabel = RowLabels.of(rowIndex);
                for (int n = 1; n <= block.seatsPerRow(); n++) {
                    Seat seat = new Seat();
                    seat.setHall(hall);
                    seat.setRowLabel(rowLabel);
                    seat.setSeatNumber(n);
                    seat.setSeatType(block.seatType());
                    seats.add(seat);
                }
            }
        }
        seatRepository.saveAll(seats);
        hall.setTotalCapacity(seats.size());

        return getDetail(id);
    }
}
