package org.saket.eventbooking.event.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.saket.eventbooking.event.dto.EventSearchCriteria;
import org.saket.eventbooking.event.entity.Event;
import org.saket.eventbooking.event.entity.EventArtist;
import org.saket.eventbooking.event.enums.EventStatus;
import org.saket.eventbooking.location.entity.Location;
import org.saket.eventbooking.session.entity.Session;
import org.saket.eventbooking.session.enums.SessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Public event search. An event is listed when it is PUBLISHED and has at least one SCHEDULED session
 * matching the filters; it's ordered by its earliest matching session. Queried from the session side
 * (sessions grouped by event) because date and city live on Session/Location, not Event.
 * Every filter is a bound parameter; nothing is concatenated into the query.
 */
@Repository
public class EventSearchRepository {

    /** One page of matching event ids with the aggregates computed over their matching sessions. */
    public record Row(UUID eventId, Instant nextSessionStart, BigDecimal startingPrice) {}

    @PersistenceContext
    private EntityManager entityManager;

    public Page<Row> search(EventSearchCriteria criteria, Instant now, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Session> session = query.from(Session.class);
        Join<Session, Event> event = session.join("event");
        Join<Session, Location> location = session.join("location");
        Expression<Instant> nextStart = cb.least(session.<Instant>get("startTime"));
        Expression<BigDecimal> minPrice = cb.min(session.get("basePrice"));

        query.multiselect(event.get("id"), nextStart, minPrice)
                .where(predicates(cb, query, session, event, location, criteria, now))
                .groupBy(event.get("id"), event.get("title"));
        if (criteria.sort() == EventSearchCriteria.Sort.TITLE) {
            query.orderBy(cb.asc(event.get("title")), cb.asc(event.get("id")));
        } else {
            query.orderBy(cb.asc(nextStart), cb.asc(event.get("id")));
        }

        List<Row> rows = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList().stream()
                .map(t -> new Row(t.get(0, UUID.class), t.get(1, Instant.class), t.get(2, BigDecimal.class)))
                .toList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Session> countSession = countQuery.from(Session.class);
        Join<Session, Event> countEvent = countSession.join("event");
        Join<Session, Location> countLocation = countSession.join("location");
        countQuery.select(cb.countDistinct(countEvent.get("id")))
                .where(predicates(cb, countQuery, countSession, countEvent, countLocation, criteria, now));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(rows, pageable, total);
    }

    private static Predicate[] predicates(CriteriaBuilder cb, CriteriaQuery<?> query, Root<Session> session,
                                          Join<Session, Event> event, Join<Session, Location> location,
                                          EventSearchCriteria criteria, Instant now) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(event.get("status"), EventStatus.PUBLISHED));
        predicates.add(cb.equal(session.get("status"), SessionStatus.SCHEDULED));

        Instant from = criteria.from() != null && criteria.from().isAfter(now) ? criteria.from() : now;
        predicates.add(cb.greaterThanOrEqualTo(session.get("startTime"), from));
        if (criteria.to() != null) {
            predicates.add(cb.lessThan(session.get("startTime"), criteria.to()));
        }
        if (hasText(criteria.city())) {
            predicates.add(cb.equal(cb.lower(location.get("city")), criteria.city().trim().toLowerCase(Locale.ROOT)));
        }
        if (hasText(criteria.category())) {
            predicates.add(cb.equal(cb.lower(event.get("category")), criteria.category().trim().toLowerCase(Locale.ROOT)));
        }
        if (hasText(criteria.query())) {
            String pattern = "%" + escapeLike(criteria.query().trim().toLowerCase(Locale.ROOT)) + "%";
            predicates.add(cb.like(cb.lower(event.get("title")), pattern, '\\'));
        }
        if (Boolean.TRUE.equals(criteria.featured())) {
            predicates.add(cb.isTrue(event.get("isFeatured")));
        }
        if (criteria.artistId() != null) {
            Subquery<UUID> lineUp = query.subquery(UUID.class);
            Root<EventArtist> eventArtist = lineUp.from(EventArtist.class);
            lineUp.select(eventArtist.get("event").get("id"))
                    .where(cb.equal(eventArtist.get("event").get("id"), event.get("id")),
                            cb.equal(eventArtist.get("artist").get("id"), criteria.artistId()));
            predicates.add(cb.exists(lineUp));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
