package org.saket.eventbooking.support;

import org.saket.eventbooking.event.dto.EventRequest;
import org.saket.eventbooking.event.dto.EventResponse;
import org.saket.eventbooking.event.enums.EventStatus;
import org.saket.eventbooking.event.service.EventService;
import org.saket.eventbooking.location.dto.HallDetailResponse;
import org.saket.eventbooking.location.dto.HallRequest;
import org.saket.eventbooking.location.dto.LocationRequest;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.location.dto.SeatLayoutRequest;
import org.saket.eventbooking.location.enums.SeatType;
import org.saket.eventbooking.location.enums.VenueType;
import org.saket.eventbooking.location.service.HallService;
import org.saket.eventbooking.location.service.LocationService;
import org.saket.eventbooking.session.dto.SessionRequest;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.dto.TicketTierRequest;
import org.saket.eventbooking.session.enums.PricingMode;
import org.saket.eventbooking.session.enums.SeatingType;
import org.saket.eventbooking.session.service.SessionService;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Builds catalog data through the real services (fast, and exercises the same validation). */
public class CatalogFixtures {

    @Autowired
    LocationService locationService;
    @Autowired
    HallService hallService;
    @Autowired
    EventService eventService;
    @Autowired
    SessionService sessionService;

    public LocationResponse location(String city) {
        return locationService.create(new LocationRequest("Venue " + UUID.randomUUID().toString().substring(0, 6),
                "1 Test Street", city, VenueType.OFFLINE));
    }

    /** A hall with {@code rows} REGULAR rows and one RECLINER back row, {@code seatsPerRow} wide. */
    public HallDetailResponse hall(UUID locationId, int rows, int seatsPerRow) {
        UUID hallId = hallService.create(locationId, new HallRequest("Hall " + UUID.randomUUID().toString().substring(0, 6))).id();
        return hallService.replaceLayout(hallId, new SeatLayoutRequest(List.of(
                new SeatLayoutRequest.Block(rows, seatsPerRow, SeatType.REGULAR),
                new SeatLayoutRequest.Block(1, seatsPerRow, SeatType.RECLINER))));
    }

    public EventResponse event(String title, String category, EventStatus status) {
        return eventService.create(new EventRequest(title, "A test event", category, null, status,
                "English", 90, "U", null, null, false));
    }

    public EventResponse publishedEvent(String title) {
        return event(title, "Music", EventStatus.PUBLISHED);
    }

    public SessionResponse assignedSession(UUID eventId, UUID locationId, UUID hallId, Instant start, String basePrice) {
        return sessionService.create(eventId, new SessionRequest(locationId, hallId, start, start.plus(Duration.ofHours(2)),
                SeatingType.ASSIGNED_SEATING, null, null, new BigDecimal(basePrice), null));
    }

    public SessionResponse flatSession(UUID eventId, UUID locationId, Instant start, int capacity, String price) {
        return sessionService.create(eventId, new SessionRequest(locationId, null, start, start.plus(Duration.ofHours(3)),
                SeatingType.GENERAL_ADMISSION, PricingMode.FLAT, capacity, new BigDecimal(price), null));
    }

    public SessionResponse tieredSession(UUID eventId, UUID locationId, Instant start, List<TicketTierRequest> tiers) {
        return sessionService.create(eventId, new SessionRequest(locationId, null, start, start.plus(Duration.ofHours(3)),
                SeatingType.GENERAL_ADMISSION, PricingMode.TIERED, null, null, tiers));
    }

    public static Instant daysFromNow(int days) {
        return Instant.now().plus(Duration.ofDays(days));
    }
}
