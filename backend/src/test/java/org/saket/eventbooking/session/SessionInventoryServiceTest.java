package org.saket.eventbooking.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.common.exception.BadRequestException;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.event.enums.EventStatus;
import org.saket.eventbooking.location.dto.HallDetailResponse;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.session.dto.SeatMapResponse;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.dto.SessionUpdateRequest;
import org.saket.eventbooking.session.dto.TicketTierRequest;
import org.saket.eventbooking.session.enums.SessionSeatStatus;
import org.saket.eventbooking.session.enums.SessionStatus;
import org.saket.eventbooking.session.service.SessionInventoryService;
import org.saket.eventbooking.session.service.SessionService;
import org.saket.eventbooking.session.service.seating.Hold;
import org.saket.eventbooking.session.service.seating.HoldRequest;
import org.saket.eventbooking.session.service.seating.HeldInventory;
import org.saket.eventbooking.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;

class SessionInventoryServiceTest extends IntegrationTest {

    @Autowired SessionInventoryService inventory;
    @Autowired SessionService sessionService;
    @Autowired TransactionTemplate tx;

    private UUID eventId;
    private LocationResponse venue;
    private HallDetailResponse hall; // 1 REGULAR row + 1 RECLINER row, 3 seats each

    @BeforeEach
    void setUp() {
        eventId = fixtures.publishedEvent(unique("Inventory")).id();
        venue = fixtures.location(unique("Stockton"));
        hall = fixtures.hall(venue.id(), 1, 3);
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private Hold hold(UUID sessionId, HoldRequest request) {
        return inTx(() -> inventory.hold(sessionId, request));
    }

    @Test
    void flatHoldDecrementsCapacityAndReleaseRestoresIt() {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(3), 5, "400");

        Hold held = hold(session.id(), new HoldRequest(null, 3, null));
        assertThat(held.totalAmount()).isEqualByComparingTo("1200");
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(2);

        assertThatThrownBy(() -> hold(session.id(), new HoldRequest(null, 3, null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Only 2 tickets left");

        inTx(() -> { inventory.release(new HeldInventory(session.id(), null, 3, null)); return null; });
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(5);
    }

    @Test
    void tieredHoldUsesTheTierPriceAndCapacity() {
        SessionResponse session = fixtures.tieredSession(eventId, venue.id(), daysFromNow(3), List.of(
                new TicketTierRequest("Standard", new BigDecimal("500"), 10),
                new TicketTierRequest("VIP", new BigDecimal("2000"), 2)));
        UUID vip = session.tiers().stream().filter(t -> t.name().equals("VIP")).findFirst().orElseThrow().id();

        Hold held = hold(session.id(), new HoldRequest(vip, 2, null));
        assertThat(held.totalAmount()).isEqualByComparingTo("4000");
        assertThatThrownBy(() -> hold(session.id(), new HoldRequest(vip, 1, null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("VIP");

        inTx(() -> { inventory.release(new HeldInventory(session.id(), vip, 2, null)); return null; });
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(12);
    }

    @Test
    void assignedHoldLocksExactlyTheChosenSeats() {
        SessionResponse session = fixtures.assignedSession(eventId, venue.id(), hall.id(), daysFromNow(3), "300");
        SeatMapResponse map = sessionService.seatMap(session.id());
        UUID regular = map.seats().get(0).id();
        UUID recliner = map.seats().get(5).id();

        Hold held = hold(session.id(), new HoldRequest(null, null, List.of(recliner, regular)));
        assertThat(held.totalAmount()).isEqualByComparingTo("900"); // 300 + 600
        SeatMapResponse after = sessionService.seatMap(session.id());
        assertThat(after.seats().get(0).status()).isEqualTo(SessionSeatStatus.LOCKED);
        assertThat(after.seats().get(5).status()).isEqualTo(SessionSeatStatus.LOCKED);
        assertThat(after.seats().get(1).status()).isEqualTo(SessionSeatStatus.AVAILABLE);

        // overlapping request fails as a whole: nothing new gets locked
        UUID free = map.seats().get(1).id();
        assertThatThrownBy(() -> hold(session.id(), new HoldRequest(null, null, List.of(free, regular))))
                .isInstanceOf(ConflictException.class);
        assertThat(sessionService.seatMap(session.id()).seats().get(1).status()).isEqualTo(SessionSeatStatus.AVAILABLE);

        inTx(() -> { inventory.release(new HeldInventory(session.id(), null, null, List.of(regular, recliner))); return null; });
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(6);
    }

    @Test
    void requestShapeMustMatchTheSessionMode() {
        SessionResponse flat = fixtures.flatSession(eventId, venue.id(), daysFromNow(3), 5, "400");
        SessionResponse assigned = fixtures.assignedSession(eventId, venue.id(), hall.id(), daysFromNow(3), "300");
        UUID seat = sessionService.seatMap(assigned.id()).seats().getFirst().id();
        UUID otherSessionSeat = sessionService.seatMap(
                fixtures.assignedSession(eventId, venue.id(), hall.id(), daysFromNow(4), "300").id()).seats().getFirst().id();

        assertThatThrownBy(() -> hold(flat.id(), new HoldRequest(null, null, List.of(seat))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> hold(flat.id(), new HoldRequest(null, null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> hold(assigned.id(), new HoldRequest(null, 2, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> hold(assigned.id(), new HoldRequest(null, null, List.of(seat, seat))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> hold(assigned.id(), new HoldRequest(null, null, List.of(otherSessionSeat))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void onlyBookableSessionsCanBeHeld() {
        UUID draftEvent = fixtures.event(unique("Draft"), "Music", EventStatus.DRAFT).id();
        SessionResponse draft = fixtures.flatSession(draftEvent, venue.id(), daysFromNow(3), 5, "400");
        assertThatThrownBy(() -> hold(draft.id(), new HoldRequest(null, 1, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        SessionResponse cancelled = fixtures.flatSession(eventId, venue.id(), daysFromNow(3), 5, "400");
        sessionService.update(cancelled.id(), new SessionUpdateRequest(cancelled.startTime(), cancelled.endTime(),
                SessionStatus.CANCELLED));
        assertThatThrownBy(() -> hold(cancelled.id(), new HoldRequest(null, 1, null)))
                .isInstanceOf(ConflictException.class);

        Instant past = Instant.now().minus(Duration.ofHours(1));
        SessionResponse started = fixtures.flatSession(eventId, venue.id(), past, 5, "400");
        assertThatThrownBy(() -> hold(started.id(), new HoldRequest(null, 1, null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("started");
    }

    @Test
    void holdingOutsideATransactionIsRefused() {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(3), 5, "400");
        assertThatThrownBy(() -> inventory.hold(session.id(), new HoldRequest(null, 1, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
