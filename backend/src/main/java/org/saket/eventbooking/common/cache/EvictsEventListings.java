package org.saket.eventbooking.common.cache;

import org.springframework.cache.annotation.CacheEvict;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Clears every cached event-list page and the category list. Put it on any admin write that can change
 * what the public listing shows (events, line-ups, sessions, tiers, venues). Listing keys depend on the
 * filters, so a targeted eviction isn't possible; clearing is cheap at this size.
 * The cache manager is transaction-aware, so the eviction happens after the write commits.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@CacheEvict(cacheNames = {CacheNames.EVENT_LIST, CacheNames.EVENT_CATEGORIES}, allEntries = true)
public @interface EvictsEventListings {
}
