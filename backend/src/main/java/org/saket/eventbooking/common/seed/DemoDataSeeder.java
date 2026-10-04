package org.saket.eventbooking.common.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.event.dto.ArtistRequest;
import org.saket.eventbooking.event.dto.EventArtistsRequest;
import org.saket.eventbooking.event.dto.EventImageRequest;
import org.saket.eventbooking.event.dto.EventRequest;
import org.saket.eventbooking.event.enums.EventStatus;
import org.saket.eventbooking.event.service.ArtistService;
import org.saket.eventbooking.event.service.EventService;
import org.saket.eventbooking.location.dto.HallRequest;
import org.saket.eventbooking.location.dto.LocationRequest;
import org.saket.eventbooking.location.dto.SeatLayoutRequest;
import org.saket.eventbooking.location.enums.SeatType;
import org.saket.eventbooking.location.enums.VenueType;
import org.saket.eventbooking.location.service.HallService;
import org.saket.eventbooking.location.service.LocationService;
import org.saket.eventbooking.session.dto.SessionRequest;
import org.saket.eventbooking.session.dto.TicketTierRequest;
import org.saket.eventbooking.session.enums.PricingMode;
import org.saket.eventbooking.session.enums.SeatingType;
import org.saket.eventbooking.session.service.SessionService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Demo catalog for local development and the portfolio deployment: cities, venues, halls with seat
 * layouts, artists, and about ten published events whose sessions cover all three seating modes.
 * Everything goes through the real services, so it obeys the same validation as the admin API.
 * Runs only when {@code app.seed.demo-data=true} and the database has no events yet.
 * All names are fictional.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.seed.demo-data", havingValue = "true")
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LocationService locationService;
    private final HallService hallService;
    private final ArtistService artistService;
    private final EventService eventService;
    private final SessionService sessionService;

    @Override
    @Transactional // all-or-nothing: a failed seed leaves no half-built catalog behind
    public void run(ApplicationArguments args) {
        if (eventService.listForAdmin(null, null, PageRequest.of(0, 1)).totalElements() > 0) {
            log.info("Demo data seeding skipped: events already exist");
            return;
        }
        seed();
    }

    @Transactional
    public void seed() {
        // ---- venues
        UUID harbourline = location("Harbourline Arena", "Gate 3, Seaface Road, Worli", "Mumbai", VenueType.OFFLINE);
        UUID copperleaf = location("Copperleaf Amphitheatre", "14 Lakeview Layout, Hebbal", "Bengaluru", VenueType.OFFLINE);
        UUID velvetRoom = location("The Velvet Room", "2nd Floor, 88 Brigade Lane", "Bengaluru", VenueType.OFFLINE);
        UUID saffron = location("Saffron Screens Multiplex", "Riverside Mall, Baner Road", "Pune", VenueType.OFFLINE);
        UUID levelUp = location("Level Up Arcade", "Unit 5, Koregaon Park Annexe", "Pune", VenueType.OFFLINE);
        UUID lantern = location("Grand Lantern Theatre", "7 Mandi House Circle", "Delhi", VenueType.OFFLINE);
        UUID riverside = location("Riverside Lawns", "Near Old Bridge, Hussain Sagar", "Hyderabad", VenueType.OFFLINE);
        UUID online = location("StreamStage Online", null, "Online", VenueType.ONLINE);

        // ---- halls with seat layouts
        UUID screen1 = hall(saffron, "Screen 1",
                block(8, 14, SeatType.REGULAR), block(3, 12, SeatType.PREMIUM), block(1, 8, SeatType.RECLINER));
        UUID screen2 = hall(saffron, "Screen 2 (Recliner Lounge)",
                block(4, 10, SeatType.PREMIUM), block(2, 8, SeatType.RECLINER));
        UUID mainHall = hall(lantern, "Main Hall",
                block(10, 20, SeatType.REGULAR), block(4, 16, SeatType.PREMIUM));
        UUID studio = hall(lantern, "Studio Stage", block(6, 12, SeatType.REGULAR));

        // ---- artists
        UUID neonHarbor = artist("Neon Harbor", "Indie Rock", "A four-piece known for sea-shanty choruses over fuzzed-out guitars.");
        UUID monsoons = artist("Aarav & The Monsoons", "Folk Rock", "Rain songs, mandolins and a lot of audience singalongs.");
        UUID kiran = artist("DJ Kiran Solace", "Electronic", "Sunset-to-sunrise sets built on deep house and live tabla loops.");
        UUID staticBloom = artist("Static Bloom", "Electronic", "Modular-synth duo, warm-up act of choice for open-air nights.");
        UUID tara = artist("Tara Sen", "Stand-up Comedy", "Observational comedy about over-planning, family groups and group chats.");
        UUID rohan = artist("Rohan Bhalla", "Stand-up Comedy", "Long-form storytelling from a decade of budget air travel.");
        UUID lanternPlayers = artist("The Lantern Players", "Theatre", "Repertory company staging new Indian writing since 2012.");
        UUID mira = artist("Mira Kaveri", "Carnatic Vocal", "Classical vocalist celebrated for her early-morning raga recitals.");

        // ---- events and sessions
        UUID e1 = event("Neon Harbor: Tidal Tour (Mumbai)", "Music", "English", 150, "16+", true,
                "The Tidal Tour opens at the seafront with the full new album and the old favourites.");
        lineUp(e1, neonHarbor, "headliner");
        images(e1, "neon-harbor-1", "neon-harbor-2");
        tiered(e1, harbourline, at(12, 19, 0), Duration.ofHours(3),
                tier("Early Bird", "999", 300), tier("General Admission", "1499", 2000), tier("VIP Deck", "3999", 150));

        UUID e2 = event("Neon Harbor: Tidal Tour (Bengaluru)", "Music", "English", 150, "16+", false,
                "Second stop of the Tidal Tour, under the stars at Copperleaf.");
        lineUp(e2, neonHarbor, "headliner");
        lineUp(e2, staticBloom, "support");
        images(e2, "neon-harbor-blr");
        tiered(e2, copperleaf, at(19, 18, 30), Duration.ofHours(3),
                tier("General Admission", "1299", 1500), tier("Fan Pit", "2499", 300));

        UUID e3 = event("Monsoon Folk Night", "Music", "Hindi", 120, "All ages", false,
                "An evening of rain songs on the lawns. Bring a mat; chai stalls open from 5pm.");
        lineUp(e3, monsoons, "headliner");
        images(e3, "monsoon-folk");
        flat(e3, riverside, at(9, 18, 0), Duration.ofHours(3), 1200, "799");

        UUID e4 = event("Solace Sessions: Open Air", "Music", "Instrumental", 360, "18+", true,
                "Six hours of deep house as the sun goes down over Copperleaf.");
        lineUp(e4, kiran, "headliner");
        lineUp(e4, staticBloom, "support");
        images(e4, "solace-1", "solace-2", "solace-3");
        tiered(e4, copperleaf, at(26, 16, 0), Duration.ofHours(6),
                tier("Phase 1", "1799", 500), tier("Phase 2", "2299", 1000), tier("Backstage", "5999", 50));

        UUID e5 = event("Tara Sen: Overthinking Live", "Comedy", "English", 75, "16+", true,
                "Tara's new hour on planning holidays nobody asked for.");
        lineUp(e5, tara, "headliner");
        images(e5, "overthinking");
        flat(e5, velvetRoom, at(5, 20, 0), Duration.ofMinutes(90), 120, "699");
        flat(e5, velvetRoom, at(6, 20, 0), Duration.ofMinutes(90), 120, "699");

        UUID e6 = event("Rohan Bhalla: Middle Seat", "Comedy", "Hinglish", 90, "16+", false,
                "Every story is true, which is the problem.");
        lineUp(e6, rohan, "headliner");
        images(e6, "middle-seat");
        assigned(e6, lantern, studio, at(14, 19, 30), Duration.ofMinutes(100), "599");

        UUID e7 = event("The Last Lantern", "Theatre", "Hindi", 130, "12+", false,
                "A lighthouse keeper's family argues through one long night in this new play.");
        lineUp(e7, lanternPlayers, "company");
        images(e7, "last-lantern-1", "last-lantern-2");
        assigned(e7, lantern, mainHall, at(7, 19, 0), Duration.ofMinutes(140), "450");
        assigned(e7, lantern, mainHall, at(8, 19, 0), Duration.ofMinutes(140), "450");
        assigned(e7, lantern, mainHall, at(15, 15, 0), Duration.ofMinutes(140), "400");

        UUID e8 = event("Orbit of Echoes", "Movie", "English", 148, "U/A 13+", true,
                "A salvage crew picks up a signal from a ship that left Earth a century ago.");
        images(e8, "orbit-of-echoes");
        assigned(e8, saffron, screen1, at(3, 13, 0), Duration.ofMinutes(165), "220");
        assigned(e8, saffron, screen1, at(3, 21, 30), Duration.ofMinutes(165), "260");
        assigned(e8, saffron, screen2, at(4, 18, 0), Duration.ofMinutes(165), "380");

        UUID e9 = event("The Quiet Monsoon", "Movie", "Marathi", 124, "U", false,
                "Two estranged sisters reopen their grandmother's tea shop for one last season.");
        images(e9, "quiet-monsoon");
        assigned(e9, saffron, screen2, at(5, 16, 0), Duration.ofMinutes(135), "300");

        UUID e10 = event("Raga at Dawn", "Music", "Carnatic", 120, "All ages", false,
                "A sunrise concert of morning ragas with violin and mridangam accompaniment.");
        lineUp(e10, mira, "headliner");
        images(e10, "raga-at-dawn");
        assigned(e10, lantern, mainHall, at(21, 6, 0), Duration.ofHours(2), "350");

        UUID e11 = event("Pixel Arcade Weekend Pass", "Gaming", null, 480, "All ages", false,
                "Retro cabinets, rhythm games and a VR corner. Pick how long you want to play.");
        images(e11, "pixel-arcade");
        tiered(e11, levelUp, at(10, 11, 0), Duration.ofHours(10),
                tier("1 Hour", "199", 200), tier("3 Hours", "499", 150), tier("Day Pass", "899", 80));

        UUID e12 = event("Home Studio Masterclass", "Workshop", "English", 120, "All ages", false,
                "Live online session: recording vocals at home on a small budget.");
        lineUp(e12, kiran, "host");
        images(e12, "home-studio");
        flat(e12, online, at(11, 19, 0), Duration.ofHours(2), 500, "299");

        // An unpublished event, to show the admin side of the status workflow.
        event("Winter Lights Festival (draft)", "Festival", "English", null, null, false,
                "Line-up to be announced.", EventStatus.DRAFT);

        log.info("Demo data seeded: 8 venues, 4 halls, 8 artists, 13 events");
    }

    // ---------------------------------------------------------------- helpers

    private UUID location(String name, String address, String city, VenueType type) {
        return locationService.create(new LocationRequest(name, address, city, type)).id();
    }

    private UUID hall(UUID locationId, String name, SeatLayoutRequest.Block... blocks) {
        UUID id = hallService.create(locationId, new HallRequest(name)).id();
        hallService.replaceLayout(id, new SeatLayoutRequest(List.of(blocks)));
        return id;
    }

    private static SeatLayoutRequest.Block block(int rows, int seatsPerRow, SeatType type) {
        return new SeatLayoutRequest.Block(rows, seatsPerRow, type);
    }

    private UUID artist(String name, String genre, String bio) {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        return artistService.create(new ArtistRequest(name, bio, imageUrl("artist-" + slug), genre)).id();
    }

    private UUID event(String title, String category, String language, Integer minutes, String age,
                       boolean featured, String description) {
        return event(title, category, language, minutes, age, featured, description, EventStatus.PUBLISHED);
    }

    private UUID event(String title, String category, String language, Integer minutes, String age,
                       boolean featured, String description, EventStatus status) {
        String slug = title.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        return eventService.create(new EventRequest(title, description, category, imageUrl("event-" + slug), status,
                language, minutes, age, null,
                "Tickets are non-transferable. Entry closes 30 minutes after start.", featured)).id();
    }

    /** Appends to the event's line-up (the service replaces the whole list, so re-send existing entries). */
    private void lineUp(UUID eventId, UUID artistId, String role) {
        var current = eventService.getDetail(eventId).artists().stream()
                .map(a -> new EventArtistsRequest.Entry(a.artistId(), a.role()));
        var entries = java.util.stream.Stream.concat(current,
                java.util.stream.Stream.of(new EventArtistsRequest.Entry(artistId, role))).toList();
        eventService.replaceArtists(eventId, new EventArtistsRequest(entries));
    }

    private void images(UUID eventId, String... seeds) {
        for (int i = 0; i < seeds.length; i++) {
            eventService.addImage(eventId, new EventImageRequest(imageUrl(seeds[i]), i));
        }
    }

    /** Placeholder photos keyed by a seed, so each event gets a stable image. */
    private static String imageUrl(String seed) {
        return "https://picsum.photos/seed/" + seed + "/1200/675";
    }

    private static TicketTierRequest tier(String name, String price, int capacity) {
        return new TicketTierRequest(name, new BigDecimal(price), capacity);
    }

    /** {@code daysFromToday} at the given IST wall-clock time. */
    private static Instant at(int daysFromToday, int hour, int minute) {
        return LocalDate.now(IST).plusDays(daysFromToday).atTime(LocalTime.of(hour, minute)).atZone(IST).toInstant();
    }

    private void assigned(UUID eventId, UUID locationId, UUID hallId, Instant start, Duration length, String basePrice) {
        sessionService.create(eventId, new SessionRequest(locationId, hallId, start, start.plus(length),
                SeatingType.ASSIGNED_SEATING, null, null, new BigDecimal(basePrice), null));
    }

    private void flat(UUID eventId, UUID locationId, Instant start, Duration length, int capacity, String price) {
        sessionService.create(eventId, new SessionRequest(locationId, null, start, start.plus(length),
                SeatingType.GENERAL_ADMISSION, PricingMode.FLAT, capacity, new BigDecimal(price), null));
    }

    private void tiered(UUID eventId, UUID locationId, Instant start, Duration length, TicketTierRequest... tiers) {
        sessionService.create(eventId, new SessionRequest(locationId, null, start, start.plus(length),
                SeatingType.GENERAL_ADMISSION, PricingMode.TIERED, null, null, List.of(tiers)));
    }
}
