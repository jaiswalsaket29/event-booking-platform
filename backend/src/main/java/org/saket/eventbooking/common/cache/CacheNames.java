package org.saket.eventbooking.common.cache;

/**
 * Redis caches for the public catalog. Only slow-changing data is cached: live availability
 * ({@code ticketsAvailable}, seat maps, session lists) is always read from the database.
 */
public final class CacheNames {

    /** Public event search pages ({@code PageResponse<EventSummaryResponse>}). Short TTL. */
    public static final String EVENT_LIST = "event-list";

    /** Published categories ({@code List<String>}). Evicted with {@link #EVENT_LIST}. */
    public static final String EVENT_CATEGORIES = "event-categories";

    /** Published event + line-up + images ({@code EventDetailResponse}), keyed by event id. No sessions. */
    public static final String EVENT_DETAIL = "event-detail";

    private CacheNames() {
    }
}
