package org.saket.eventbooking.session.service.seating;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.exception.BadRequestException;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.session.entity.Session;
import org.saket.eventbooking.session.entity.SessionSeat;
import org.saket.eventbooking.session.enums.SeatingType;
import org.saket.eventbooking.session.enums.SessionSeatStatus;
import org.saket.eventbooking.session.repository.SessionSeatRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Specific seats: each {@link SessionSeat} row goes AVAILABLE -> LOCKED while held. */
@Component
@RequiredArgsConstructor
public class AssignedSeatingStrategy implements SeatingStrategy {

    private final SessionSeatRepository sessionSeatRepository;

    @Override
    public boolean supports(Session session) {
        return session.getSeatingType() == SeatingType.ASSIGNED_SEATING;
    }

    @Override
    public Hold hold(Session session, HoldRequest request) {
        if (request.ticketTierId() != null || request.quantity() != null) {
            throw new BadRequestException("This session has assigned seating: send sessionSeatIds, not a tier or quantity");
        }
        List<UUID> ids = request.seatIds();
        if (ids.isEmpty()) {
            throw new BadRequestException("Choose at least one seat");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new BadRequestException("The same seat was selected twice");
        }

        List<SessionSeat> seats = sessionSeatRepository.lockBySessionIdAndIds(session.getId(), ids);
        if (seats.size() != ids.size()) {
            throw new BadRequestException("One or more seats don't belong to this session");
        }
        if (seats.stream().anyMatch(s -> s.getStatus() != SessionSeatStatus.AVAILABLE)) {
            throw new ConflictException("One or more of the selected seats are no longer available");
        }

        Instant now = Instant.now();
        BigDecimal total = BigDecimal.ZERO;
        for (SessionSeat seat : seats) {
            seat.setStatus(SessionSeatStatus.LOCKED);
            seat.setLockedAt(now);
            total = total.add(seat.getPrice());
        }
        return new Hold(session, null, null, seats, total);
    }

    @Override
    public void release(Session session, ReleaseRequest request) {
        if (request.sessionSeatIds() == null || request.sessionSeatIds().isEmpty()) {
            return;
        }
        for (SessionSeat seat : sessionSeatRepository.lockBySessionIdAndIds(session.getId(), request.sessionSeatIds())) {
            // Only undo our own hold; a BOOKED seat (paid) is never released here.
            if (seat.getStatus() == SessionSeatStatus.LOCKED) {
                seat.setStatus(SessionSeatStatus.AVAILABLE);
                seat.setLockedAt(null);
            }
        }
    }
}
