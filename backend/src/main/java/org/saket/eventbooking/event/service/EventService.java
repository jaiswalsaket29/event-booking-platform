package org.saket.eventbooking.event.service;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.cache.CacheNames;
import org.saket.eventbooking.common.cache.EvictsEventListings;
import org.saket.eventbooking.common.dto.PageResponse;
import org.saket.eventbooking.common.exception.BadRequestException;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.event.dto.EventArtistsRequest;
import org.saket.eventbooking.event.dto.EventDetailResponse;
import org.saket.eventbooking.event.dto.EventImageRequest;
import org.saket.eventbooking.event.dto.EventRequest;
import org.saket.eventbooking.event.dto.EventResponse;
import org.saket.eventbooking.event.dto.EventSearchCriteria;
import org.saket.eventbooking.event.dto.EventSummaryResponse;
import org.saket.eventbooking.event.entity.Event;
import org.saket.eventbooking.event.entity.EventArtist;
import org.saket.eventbooking.event.entity.EventImage;
import org.saket.eventbooking.event.enums.EventStatus;
import org.saket.eventbooking.event.repository.EventArtistRepository;
import org.saket.eventbooking.event.repository.EventImageRepository;
import org.saket.eventbooking.event.repository.EventRepository;
import org.saket.eventbooking.event.repository.EventSearchRepository;
import org.saket.eventbooking.event.event.EventCancelledEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final EventArtistRepository eventArtistRepository;
    private final EventImageRepository eventImageRepository;
    private final ArtistService artistService;
    private final EventSearchRepository eventSearchRepository;
    private final ApplicationEventPublisher events;

    /**
     * Public listing: published events with an upcoming scheduled session matching the filters.
     * Cached briefly (it holds no availability, only next date and "from" price); free-text searches are
     * not cached, since their keys are unbounded.
     */
    @Cacheable(cacheNames = CacheNames.EVENT_LIST,
            key = "#criteria.toString() + '|' + #pageable.pageNumber + '|' + #pageable.pageSize",
            condition = "#criteria.query() == null || #criteria.query().isBlank()")
    @Transactional(readOnly = true)
    public PageResponse<EventSummaryResponse> searchPublished(EventSearchCriteria criteria, Pageable pageable) {
        if (criteria.from() != null && criteria.to() != null && !criteria.to().isAfter(criteria.from())) {
            throw new BadRequestException("'to' must be after 'from'");
        }
        Page<EventSearchRepository.Row> rows = eventSearchRepository.search(criteria, Instant.now(), pageable);
        Map<UUID, Event> events = eventRepository.findAllById(rows.map(EventSearchRepository.Row::eventId).toList())
                .stream().collect(Collectors.toMap(Event::getId, Function.identity()));
        return PageResponse.of(rows, row -> {
            Event e = events.get(row.eventId());
            return new EventSummaryResponse(e.getId(), e.getTitle(), e.getCategory(), e.getImageUrl(), e.getLanguage(),
                    e.getDurationMinutes(), e.getAgeRestriction(), e.isFeatured(), row.nextSessionStart(), row.startingPrice());
        });
    }

    @Cacheable(cacheNames = CacheNames.EVENT_CATEGORIES, key = "'all'")
    @Transactional(readOnly = true)
    public List<String> listPublishedCategories() {
        return eventRepository.findDistinctCategories(EventStatus.PUBLISHED);
    }

    /** Admin listing: every status, optional status filter and title search. */
    @Transactional(readOnly = true)
    public PageResponse<EventResponse> listForAdmin(EventStatus status, String query, Pageable pageable) {
        Specification<Event> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (query != null && !query.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("title")),
                        "%" + escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%", '\\'));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return PageResponse.of(eventRepository.findAll(spec, pageable), EventResponse::from);
    }

    @Transactional(readOnly = true)
    public EventDetailResponse getDetail(UUID id) {
        return toDetail(getEntity(id));
    }

    /** Public view: anything that isn't PUBLISHED doesn't exist. Cached; sessions are not part of it. */
    @Cacheable(cacheNames = CacheNames.EVENT_DETAIL, key = "#id.toString()")
    @Transactional(readOnly = true)
    public EventDetailResponse getPublishedDetail(UUID id) {
        Event event = getPublishedEntity(id);
        return toDetail(event);
    }

    @Transactional(readOnly = true)
    public Event getEntity(UUID id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event", id));
    }

    @Transactional(readOnly = true)
    public Event getPublishedEntity(UUID id) {
        return eventRepository.findById(id)
                .filter(e -> e.getStatus() == EventStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("Event", id));
    }

    @EvictsEventListings
    @Transactional
    public EventResponse create(EventRequest request) {
        Event event = new Event();
        event.setStatus(request.status() != null ? request.status() : EventStatus.DRAFT);
        apply(event, request);
        return EventResponse.from(eventRepository.save(event));
    }

    /**
     * Moving to CANCELLED cascades in the same transaction: {@link EventCancelledEvent} → its scheduled
     * sessions are cancelled → their bookings are cancelled (see design-decisions.md). A cancelled event
     * can't be reopened, because the cascade can't be undone.
     */
    @EvictsEventListings
    @CacheEvict(cacheNames = CacheNames.EVENT_DETAIL, key = "#id.toString()")
    @Transactional
    public EventResponse update(UUID id, EventRequest request) {
        Event event = getEntity(id);
        if (request.status() != null && request.status() != event.getStatus()) {
            if (event.getStatus() == EventStatus.CANCELLED) {
                throw new BadRequestException("A cancelled event can't be reopened");
            }
            event.setStatus(request.status());
            if (request.status() == EventStatus.CANCELLED) {
                events.publishEvent(new EventCancelledEvent(event.getId()));
            }
        }
        apply(event, request);
        return EventResponse.from(event);
    }

    /** Images and line-up go with the event (ON DELETE CASCADE); 409 while sessions exist. */
    @EvictsEventListings
    @CacheEvict(cacheNames = CacheNames.EVENT_DETAIL, key = "#id.toString()")
    @Transactional
    public void delete(UUID id) {
        Event event = getEntity(id);
        try {
            eventRepository.delete(event);
            eventRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Event has sessions and can't be deleted; cancel it instead");
        }
    }

    @EvictsEventListings
    @CacheEvict(cacheNames = CacheNames.EVENT_DETAIL, key = "#eventId.toString()")
    @Transactional
    public EventDetailResponse replaceArtists(UUID eventId, EventArtistsRequest request) {
        Event event = getEntity(eventId);

        Set<UUID> seen = new HashSet<>();
        for (EventArtistsRequest.Entry entry : request.artists()) {
            if (!seen.add(entry.artistId())) {
                throw new BadRequestException("Artist listed twice: " + entry.artistId());
            }
        }

        eventArtistRepository.deleteByEventId(eventId);
        for (EventArtistsRequest.Entry entry : request.artists()) {
            EventArtist link = new EventArtist();
            link.setEvent(event);
            link.setArtist(artistService.getEntity(entry.artistId()));
            link.setRole(entry.role() == null || entry.role().isBlank() ? null : entry.role().trim());
            eventArtistRepository.save(link);
        }
        return toDetail(event);
    }

    @EvictsEventListings
    @CacheEvict(cacheNames = CacheNames.EVENT_DETAIL, key = "#eventId.toString()")
    @Transactional
    public EventDetailResponse addImage(UUID eventId, EventImageRequest request) {
        Event event = getEntity(eventId);
        EventImage image = new EventImage();
        image.setEvent(event);
        image.setImageUrl(request.imageUrl().trim());
        image.setSortOrder(request.sortOrder());
        eventImageRepository.save(image);
        return toDetail(event);
    }

    @EvictsEventListings
    @CacheEvict(cacheNames = CacheNames.EVENT_DETAIL, key = "#eventId.toString()")
    @Transactional
    public void deleteImage(UUID eventId, UUID imageId) {
        EventImage image = eventImageRepository.findByIdAndEventId(imageId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event image", imageId));
        eventImageRepository.delete(image);
    }

    private EventDetailResponse toDetail(Event event) {
        List<EventDetailResponse.EventArtistResponse> artists = eventArtistRepository
                .findByEventIdWithArtist(event.getId()).stream()
                .map(ea -> new EventDetailResponse.EventArtistResponse(ea.getArtist().getId(), ea.getArtist().getName(),
                        ea.getArtist().getImageUrl(), ea.getArtist().getGenre(), ea.getRole()))
                .toList();
        List<EventDetailResponse.EventImageResponse> images = eventImageRepository
                .findByEventIdOrderBySortOrderAscIdAsc(event.getId()).stream()
                .map(img -> new EventDetailResponse.EventImageResponse(img.getId(), img.getImageUrl(), img.getSortOrder()))
                .toList();
        return new EventDetailResponse(EventResponse.from(event), artists, images);
    }

    private static void apply(Event event, EventRequest request) {
        event.setTitle(request.title().trim());
        event.setDescription(request.description());
        event.setCategory(request.category().trim());
        event.setImageUrl(request.imageUrl());
        event.setLanguage(request.language());
        event.setDurationMinutes(request.durationMinutes());
        event.setAgeRestriction(request.ageRestriction());
        event.setHighlights(request.highlights());
        event.setTermsAndConditions(request.termsAndConditions());
        event.setFeatured(request.featured());
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
