package org.saket.eventbooking.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.booking.dto.CreateBookingRequest;
import org.saket.eventbooking.booking.service.BookingService;
import org.saket.eventbooking.common.cache.CacheNames;
import org.saket.eventbooking.event.dto.EventResponse;
import org.saket.eventbooking.event.entity.Event;
import org.saket.eventbooking.event.repository.EventRepository;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.user.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Redis cache-aside on the public catalog: slow-changing parts are served from the cache, admin edits
 * evict them, and availability is never cached.
 */
class EventCacheTest extends IntegrationTest {

    @Autowired EventRepository eventRepository;
    @Autowired BookingService bookingService;
    @Autowired StringRedisTemplate redis;

    private String city;
    private LocationResponse venue;
    private EventResponse event;
    private SessionResponse session;

    @BeforeEach
    void setUp() {
        city = unique("Cachetown");
        venue = fixtures.location(city);
        event = fixtures.publishedEvent(unique("Cached Gig"));
        session = fixtures.flatSession(event.id(), venue.id(), daysFromNow(4), 50, "400");
    }

    @Test
    void eventDetailIsServedFromCacheUntilAnAdminEditEvictsIt() throws Exception {
        mockMvc.perform(get("/api/v1/events/{id}", event.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.title").value(event.title()));
        assertThat(redis.hasKey(CacheNames.EVENT_DETAIL + "::" + event.id())).isTrue();

        // a change that bypasses the services isn't seen: the response comes from Redis
        Event raw = eventRepository.findById(event.id()).orElseThrow();
        raw.setTitle("Changed behind the cache");
        eventRepository.save(raw);
        mockMvc.perform(get("/api/v1/events/{id}", event.id()))
                .andExpect(jsonPath("$.event.title").value(event.title()));

        // an admin edit evicts after commit, so the next read sees the new data
        mockMvc.perform(put("/api/v1/admin/events/{id}", event.id())
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Renamed Gig", "category": "Music", "language": "English", "featured": false}
                                """))
                .andExpect(status().isOk());
        assertThat(redis.hasKey(CacheNames.EVENT_DETAIL + "::" + event.id())).isFalse();
        mockMvc.perform(get("/api/v1/events/{id}", event.id()))
                .andExpect(jsonPath("$.event.title").value("Renamed Gig"))
                .andExpect(jsonPath("$.event.language").value("English"));
    }

    @Test
    void availabilityIsAlwaysLiveEvenWhenTheEventIsCached() throws Exception {
        mockMvc.perform(get("/api/v1/events/{id}", event.id()))
                .andExpect(jsonPath("$.sessions[0].ticketsAvailable").value(50));

        var buyer = createUser(Role.USER, true);
        bookingService.create(buyer.getId(), new CreateBookingRequest(session.id(), null, 3, null));

        mockMvc.perform(get("/api/v1/events/{id}", event.id()))
                .andExpect(jsonPath("$.sessions[0].ticketsAvailable").value(47));
        mockMvc.perform(get("/api/v1/events/{id}/sessions", event.id()))
                .andExpect(jsonPath("$[0].ticketsAvailable").value(47));
    }

    @Test
    void listingIsCachedAndEvictedWhenSessionsChange() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("city", city))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].startingPrice").value(400));
        assertThat(cacheKeys(CacheNames.EVENT_LIST, city)).hasSize(1);

        // a cheaper session lowers the "from" price; creating it evicts every listing page
        fixtures.flatSession(event.id(), venue.id(), daysFromNow(6), 50, "250");
        assertThat(cacheKeys(CacheNames.EVENT_LIST, city)).isEmpty();
        mockMvc.perform(get("/api/v1/events").param("city", city))
                .andExpect(jsonPath("$.content[0].startingPrice").value(250));

        // a new published event in the city shows up too
        EventResponse second = fixtures.publishedEvent(unique("Second Gig"));
        fixtures.flatSession(second.id(), venue.id(), daysFromNow(9), 10, "100");
        mockMvc.perform(get("/api/v1/events").param("city", city))
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    void freeTextSearchesAreNotCached() throws Exception {
        String needle = event.title().substring(event.title().indexOf('-') + 1);
        mockMvc.perform(get("/api/v1/events").param("q", needle))
                .andExpect(jsonPath("$.content", hasSize(1)));
        assertThat(cacheKeys(CacheNames.EVENT_LIST, needle)).isEmpty();
    }

    @Test
    void categoriesAreCachedAndRefreshedWhenANewCategoryAppears() throws Exception {
        String category = unique("Puppetry");
        mockMvc.perform(get("/api/v1/events/categories")).andExpect(status().isOk());
        assertThat(redis.hasKey(CacheNames.EVENT_CATEGORIES + "::all")).isTrue();

        fixtures.event(unique("Strings Attached"), category,
                org.saket.eventbooking.event.enums.EventStatus.PUBLISHED);
        mockMvc.perform(get("/api/v1/events/categories"))
                .andExpect(jsonPath("$[?(@ == '" + category + "')]", hasSize(1)));
    }

    private Set<String> cacheKeys(String cache, String containing) {
        return redis.keys(cache + "::*" + containing + "*");
    }
}
