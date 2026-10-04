package org.saket.eventbooking.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.booking.dto.BookingResponse;
import org.saket.eventbooking.booking.dto.CreateBookingRequest;
import org.saket.eventbooking.booking.entity.Booking;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.booking.service.BookingService;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.location.dto.HallDetailResponse;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.dto.TicketTierRequest;
import org.saket.eventbooking.session.enums.SessionSeatStatus;
import org.saket.eventbooking.session.service.SessionService;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.user.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;

/**
 * Fires many simultaneous checkouts at the same inventory through the real service and database
 * (Testcontainers Postgres) and proves nothing is oversold. Each thread is a different verified user,
 * and all threads are released together from a latch so they genuinely contend for the row locks.
 */
class BookingConcurrencyTest extends IntegrationTest {

    private record Outcome(int succeeded, int soldOut, List<Throwable> unexpected) {}

    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookingRepository;
    @Autowired SessionService sessionService;

    private UUID eventId;
    private LocationResponse venue;

    @BeforeEach
    void setUp() {
        eventId = fixtures.publishedEvent(unique("Rush")).id();
        venue = fixtures.location(unique("Stampede"));
    }

    /** Runs {@code threads} bookings at once; request i is built by {@code requestFor.apply(i)}. */
    private Outcome race(int threads, IntFunction<CreateBookingRequest> requestFor) throws Exception {
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            users.add(createUser(Role.USER, true).getId());
        }
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<BookingResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return bookingService.create(users.get(n), requestFor.apply(n));
                }));
            }
            ready.await(30, TimeUnit.SECONDS);
            go.countDown();

            int succeeded = 0;
            int soldOut = 0;
            List<Throwable> unexpected = new ArrayList<>();
            for (Future<BookingResponse> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                    succeeded++;
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof ConflictException) {
                        soldOut++;
                    } else {
                        unexpected.add(e.getCause());
                    }
                }
            }
            return new Outcome(succeeded, soldOut, unexpected);
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Booking> pendingBookingsFor(UUID sessionId) {
        return bookingRepository.findAll().stream()
                .filter(b -> b.getSession().getId().equals(sessionId) && b.getStatus() == BookingStatus.PENDING)
                .toList();
    }

    @Test
    void lastFlatTicketIsSoldExactlyOnce() throws Exception {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(3), 1, "500");

        Outcome outcome = race(20, i -> new CreateBookingRequest(session.id(), null, 1, null));

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.succeeded()).isEqualTo(1);
        assertThat(outcome.soldOut()).isEqualTo(19);
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isZero();
        assertThat(pendingBookingsFor(session.id())).hasSize(1);
    }

    @Test
    void lastTicketInATierIsSoldExactlyOnce() throws Exception {
        SessionResponse session = fixtures.tieredSession(eventId, venue.id(), daysFromNow(3), List.of(
                new TicketTierRequest("Standard", new BigDecimal("700"), 100),
                new TicketTierRequest("Front Row", new BigDecimal("3000"), 1)));
        UUID frontRow = session.tiers().stream().filter(t -> t.name().equals("Front Row")).findFirst().orElseThrow().id();

        Outcome outcome = race(20, i -> new CreateBookingRequest(session.id(), frontRow, 1, null));

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.succeeded()).isEqualTo(1);
        assertThat(sessionService.get(session.id()).tiers().stream()
                .filter(t -> t.id().equals(frontRow)).findFirst().orElseThrow().availableCapacity()).isZero();
        // the other tier was untouched
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(100);
    }

    @Test
    void oneSeatCanOnlyBeHeldByOneCheckout() throws Exception {
        HallDetailResponse hall = fixtures.hall(venue.id(), 2, 5);
        SessionResponse session = fixtures.assignedSession(eventId, venue.id(), hall.id(), daysFromNow(3), "300");
        UUID seat = sessionService.seatMap(session.id()).seats().get(3).id();

        Outcome outcome = race(20, i -> new CreateBookingRequest(session.id(), null, null, List.of(seat)));

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.succeeded()).isEqualTo(1);
        assertThat(sessionService.seatMap(session.id()).seats().get(3).status()).isEqualTo(SessionSeatStatus.LOCKED);
        assertThat(pendingBookingsFor(session.id())).hasSize(1);
    }

    @Test
    void capacityIsNeverOversoldUnderLoad() throws Exception {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(3), 10, "500");

        Outcome outcome = race(30, i -> new CreateBookingRequest(session.id(), null, 1, null));

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.succeeded()).isEqualTo(10);
        assertThat(outcome.soldOut()).isEqualTo(20);
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isZero();
    }

    /**
     * Neighbouring pairs in a ring (A1+A2, A2+A3, ..., A6+A1) overlap in both directions. Locking in
     * request order could deadlock; locking in id order can't. Every request must either succeed or
     * cleanly lose (409), and no seat may end up in two bookings.
     */
    @Test
    void overlappingMultiSeatRequestsNeverDeadlockOrDoubleBook() throws Exception {
        HallDetailResponse hall = fixtures.hall(venue.id(), 1, 6);
        SessionResponse session = fixtures.assignedSession(eventId, venue.id(), hall.id(), daysFromNow(3), "300");
        List<UUID> row = sessionService.seatMap(session.id()).seats().stream().limit(6).map(s -> s.id()).toList();

        Outcome outcome = race(24, i -> {
            int a = i % 6;
            int b = (a + 1) % 6;
            // alternate the order the client lists them in, too
            List<UUID> pair = i % 2 == 0 ? List.of(row.get(a), row.get(b)) : List.of(row.get(b), row.get(a));
            return new CreateBookingRequest(session.id(), null, null, pair);
        });

        assertThat(outcome.unexpected()).as("no deadlocks or other errors").isEmpty();
        assertThat(outcome.succeeded()).isBetween(1, 3);

        Map<UUID, Integer> holdsPerSeat = new HashMap<>();
        for (Booking booking : pendingBookingsFor(session.id())) {
            for (BookingResponse.SeatInfo seat : bookingService.getMine(booking.getUser().getId(), booking.getId()).seats()) {
                holdsPerSeat.merge(seat.sessionSeatId(), 1, Integer::sum);
            }
        }
        assertThat(holdsPerSeat.values()).allMatch(count -> count == 1);
        assertThat(holdsPerSeat).hasSize(outcome.succeeded() * 2);
    }
}
