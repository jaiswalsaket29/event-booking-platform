package org.saket.eventbooking.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.booking.dto.BookingResponse;
import org.saket.eventbooking.booking.dto.CreateBookingRequest;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.booking.hold.BookingHoldStore;
import org.saket.eventbooking.booking.hold.StaleHoldSweeper;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.booking.service.BookingExpiryService;
import org.saket.eventbooking.booking.service.BookingService;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;

class BookingExpiryTest extends IntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired BookingExpiryService expiryService;
    @Autowired StaleHoldSweeper sweeper;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingHoldStore holdStore;
    @Autowired SessionService sessionService;

    private UUID userId;
    private UUID eventId;
    private LocationResponse venue;

    @BeforeEach
    void setUp() {
        userId = createUser(Role.USER, true).getId();
        eventId = fixtures.publishedEvent(unique("Expiring")).id();
        venue = fixtures.location(unique("Hourglass"));
    }

    private BookingStatus statusOf(UUID bookingId) {
        return bookingRepository.findById(bookingId).orElseThrow().getStatus();
    }

    @Test
    void expiringAFlatHoldCancelsTheBookingAndRestoresCapacityOnce() {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(4), 10, "300");
        BookingResponse booking = bookingService.create(userId, new CreateBookingRequest(session.id(), null, 4, null));
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(6);

        assertThat(expiryService.expire(booking.id())).isTrue();
        assertThat(statusOf(booking.id())).isEqualTo(BookingStatus.CANCELLED);
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(10);

        // a second expiry (Redis event + sweeper, or two instances) changes nothing
        assertThat(expiryService.expire(booking.id())).isFalse();
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(10);
    }

    @Test
    void expiringTieredAndAssignedHoldsReleasesTheirInventory() {
        SessionResponse tiered = fixtures.tieredSession(eventId, venue.id(), daysFromNow(4), List.of(
                new TicketTierRequest("Standard", new BigDecimal("500"), 5)));
        UUID tierId = tiered.tiers().getFirst().id();
        BookingResponse tierBooking = bookingService.create(userId,
                new CreateBookingRequest(tiered.id(), tierId, 5, null));
        assertThat(sessionService.get(tiered.id()).ticketsAvailable()).isZero();

        HallDetailResponse hall = fixtures.hall(venue.id(), 1, 2);
        SessionResponse assigned = fixtures.assignedSession(eventId, venue.id(), hall.id(), daysFromNow(4), "200");
        List<UUID> seats = sessionService.seatMap(assigned.id()).seats().stream().limit(2)
                .map(s -> s.id()).toList();
        BookingResponse seatBooking = bookingService.create(userId,
                new CreateBookingRequest(assigned.id(), null, null, seats));

        expiryService.expire(tierBooking.id());
        expiryService.expire(seatBooking.id());

        assertThat(sessionService.get(tiered.id()).ticketsAvailable()).isEqualTo(5);
        assertThat(sessionService.seatMap(assigned.id()).seats())
                .allSatisfy(seat -> assertThat(seat.status()).isEqualTo(SessionSeatStatus.AVAILABLE));
    }

    @Test
    void concurrentExpiriesReleaseExactlyOnce() throws Exception {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(4), 10, "300");
        BookingResponse booking = bookingService.create(userId, new CreateBookingRequest(session.id(), null, 3, null));

        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            Callable<Boolean> expire = () -> {
                start.await();
                return expiryService.expire(booking.id());
            };
            List<Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(expire));
            }
            start.countDown();
            int transitions = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    transitions++;
                }
            }
            assertThat(transitions).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(10);
    }

    @Test
    void sweeperExpiresOnlyHoldsPastTtlPlusGrace() {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(4), 10, "300");
        BookingResponse booking = bookingService.create(userId, new CreateBookingRequest(session.id(), null, 2, null));

        sweeper.expireStale(Instant.now().plus(Duration.ofMinutes(5)));
        assertThat(statusOf(booking.id())).isEqualTo(BookingStatus.PENDING);

        sweeper.expireStale(Instant.now().plus(Duration.ofMinutes(11)));
        assertThat(statusOf(booking.id())).isEqualTo(BookingStatus.CANCELLED);
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(10);
    }

    @Test
    void redisKeyExpiryReleasesTheHoldEndToEnd() throws Exception {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(4), 10, "300");
        BookingResponse booking = bookingService.create(userId, new CreateBookingRequest(session.id(), null, 2, null));
        assertThat(holdStore.remainingTtl(booking.id())).isPresent();

        // Shorten the real hold key so Redis expires it now and publishes the key-expired event.
        holdStore.place(booking.id(), Duration.ofSeconds(1));

        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (statusOf(booking.id()) == BookingStatus.PENDING && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        assertThat(statusOf(booking.id())).isEqualTo(BookingStatus.CANCELLED);
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(10);
        assertThat(holdStore.remainingTtl(booking.id())).isEmpty();
    }

    @Test
    void unrelatedOrUnknownKeysAreIgnored() {
        assertThat(BookingHoldStore.bookingIdFromKey("session:123")).isEmpty();
        assertThat(BookingHoldStore.bookingIdFromKey("booking-hold:not-a-uuid")).isEmpty();
        assertThat(expiryService.expire(UUID.randomUUID())).isFalse();
    }
}
