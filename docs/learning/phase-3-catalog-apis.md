# Phase 3: Catalog APIs (learning notes)

Phase 3 turned the data model into a usable catalog: admins build venues, events and sessions; anyone can browse them. All code lives under `backend/src/main/java/org/saket/eventbooking/`.

## 1. Request walkthroughs

### A. Admin creates an assigned-seating session

`POST /api/v1/admin/events/{eventId}/sessions` with a body like:

```json
{"locationId": "...", "hallId": "...", "startTime": "2026-11-01T13:30:00Z",
 "endTime": "2026-11-01T16:00:00Z", "seatingType": "ASSIGNED_SEATING", "basePrice": 250}
```

1. **Security filter chain** (`common/security/SecurityConfig`): `JwtAuthenticationFilter` turns the Bearer token into an `Authentication` with `ROLE_ADMIN`. The rule `/api/v1/admin/** hasRole("ADMIN")` lets it through. A USER token stops here with a JSON 403 from `RestAccessDeniedHandler`.
2. **Controller** `session/controller/AdminSessionController.create`. Class-level `@PreAuthorize("hasRole('ADMIN')")` checks again (defense in depth). `@Valid` runs Bean Validation on `SessionRequest` (`@NotNull`, `@Future startTime`, `@Digits` on prices). A failure becomes a 400 with `fieldErrors` from `GlobalExceptionHandler.handleValidation`.
3. **Service** `session/service/SessionService.create` (`@Transactional`):
   - Loads the event through `EventService.getEntity` and the location through `LocationService.getEntity`. It calls the other domains' *services*, never their repositories (the boundary rule).
   - `configureAssignedSeating` enforces the mode rules: `hallId` required; no `pricingMode`, `availableCapacity` or `tiers`; the hall must belong to the location (`hall.getLocation().getId()`); and the hall must have seats (`HallService.getSeats`).
   - `sessionRepository.save(session)`, then `generateSessionSeats` creates one `SessionSeat` per hall `Seat`, with `price = SeatPriceCalculator.priceFor(seatType, basePrice)` and status `AVAILABLE`.
   - `sessionSeatRepository.saveAll(...)`. With `hibernate.jdbc.batch_size: 50` and `order_inserts: true` in `application.yml`, Hibernate sends these as batched `INSERT INTO session_seats ...` statements rather than one round trip per seat.
4. **Response**: `toResponses` builds `SessionResponse` with two batched queries: `SessionSeatRepository.countBySessionIdsAndStatus` (a `GROUP BY session_id`) and `TicketTierRepository.findBySessionIdIn`. `ticketsAvailable` gets the same meaning in all three modes.

If anything throws, the whole transaction rolls back: no session without seats.

### B. Anonymous user lists events in a city

`GET /api/v1/events?city=Pune&from=2026-10-10T00:00:00Z&sort=DATE&page=0&size=12`

1. The filter chain permits `GET /api/v1/events/**` anonymously.
2. `event/controller/EventController.search` binds the params (`@DateTimeFormat` for instants, `@Min/@Max` on `size`, which is method validation that gives a 400 via `HandlerMethodValidationException`). It builds an `EventSearchCriteria`.
3. `EventService.searchPublished` rejects `to <= from`, then calls `event/repository/EventSearchRepository.search`.
4. `EventSearchRepository` uses the JPA **Criteria API**. The root is `Session`, joined to `Event` and `Location`, grouped by event:

   ```sql
   select e.id, min(s.start_time), min(s.base_price)
   from sessions s join events e on ... join locations l on ...
   where e.status = 'PUBLISHED' and s.status = 'SCHEDULED'
     and s.start_time >= ? and lower(l.city) = ? ...
   group by e.id, e.title
   order by min(s.start_time), e.id
   offset ? limit ?
   ```

   A second `count(distinct e.id)` query with the same predicates gives `totalElements`.
5. The service loads that page's `Event` rows with `findAllById` (one query), keeps the row order, and maps them to `EventSummaryResponse` (`nextSessionStart`, `startingPrice`), wrapped in `PageResponse`.

## 2. Key classes

| Class | What it does | What breaks without it |
|---|---|---|
| `location/service/HallService` | Hall CRUD; `replaceLayout` turns blocks (`rows x seatsPerRow x seatType`) into `Seat` rows and sets `totalCapacity` | Admins would have to create hundreds of seats one by one; capacity could disagree with seats |
| `location/service/RowLabels` | Row labels A..Z, AA, AB... and their correct sort order (Z before AA) | Seat maps would sort "AA" before "B" |
| `event/service/EventService` | Event CRUD, line-up replace, gallery, admin `Specification` search, public search | No events |
| `event/repository/EventSearchRepository` | Public listing query (group sessions by event) | No way to filter events by city/date, which live on sessions |
| `session/service/SessionService` | Mode validation, `SessionSeat` generation, tier management, seat maps, public visibility | Mixed or invalid seating modes would reach the booking phase |
| `session/service/SeatPriceCalculator` | `basePrice x multiplier`, rounded HALF_UP to 2 decimals; fails at startup if a multiplier is missing | Bad config would only surface at the first session creation |
| `session/config/SeatPricingProperties` | Typed `@ConfigurationProperties` for `app.pricing.seat-type-multipliers` | Magic numbers in code |
| `common/dto/PageResponse` | Stable JSON for pages | Clients would depend on Spring's `PageImpl` JSON, which isn't a contract |
| `common/seed/DemoDataSeeder` | Builds the demo catalog through the services | Empty app on first run |
| `contact/service/ContactService` | Stores contact messages; idempotent resolve | No admin triage queue |

## 3. Concepts used

- **Mutually exclusive modes validated at write time.** `SessionService.configureAssignedSeating` and `configureGeneralAdmission` guarantee a session is exactly one of: assigned seating, flat GA, or tiered GA. Phase 4's `SeatingStrategy` can then pick an implementation by `seatingType` and `pricingMode` and trust the data shape.
- **Price snapshot.** `SessionSeat.price` is computed once. Changing `app.pricing` later doesn't reprice existing sessions; that's deliberate (see `design-decisions.md`).
- **`@Transactional` boundaries.** Services own transactions; `readOnly = true` on reads. Controllers never touch entities; DTOs are built inside the transaction, so lazy associations load safely.
- **Lazy loading + `@EntityGraph`.** All `@ManyToOne` are LAZY. `SessionRepository.findWithDetailsById` uses `@EntityGraph(attributePaths = {"event","location","hall"})` to fetch what the response needs in one query. `EventArtistRepository.findByEventIdWithArtist` uses `join fetch`.
- **Avoiding N+1.** `SessionService.toResponses` uses one `GROUP BY` count query and one `IN (...)` tier query for a whole list of sessions.
- **Database-enforced integrity → 409.** Deleting a hall used by sessions, an artist in a line-up, or an event with sessions trips a foreign key. The service catches `DataIntegrityViolationException` and throws `ConflictException` with a clear message; `GlobalExceptionHandler` maps it to 409. Letting the DB enforce this avoids a circular service dependency (location ↔ session).
- **Bulk JPQL deletes.** `@Modifying(flushAutomatically = true) @Query("delete from Seat s where s.hall.id = :hallId")` runs one SQL `DELETE` instead of loading and deleting each entity. `flushAutomatically` makes pending changes hit the DB first.
- **Pessimistic locking (preview of Phase 4).** `TicketTierRepository.findByIdForUpdate` is `@Lock(PESSIMISTIC_WRITE)` (`SELECT ... FOR UPDATE`). An admin editing tier capacity can't interleave with a booking decrementing it. `updateTier` shifts `availableCapacity` by the capacity delta, so tickets already sold stay sold.
- **Criteria API vs JPQL vs Specification.** Admin event search uses a Spring Data `Specification` (simple predicates on one entity). Public search needs grouping, aggregates and dynamic predicates, so it uses the raw Criteria API. In both, every value is a bound parameter: no string concatenation, so no SQL injection. `LIKE` input is escaped (`escapeLike`).
- **Public visibility.** Drafts and their sessions return **404**, not 403 (`EventService.getPublishedEntity`, `SessionService.getPublicWithDetails`), so their existence doesn't leak.
- **Method-level validation on `@RequestParam`.** `@Min(1) @Max(100) int size` on `EventController.search` gives a 400 via `HandlerMethodValidationException`.
- **Conditional beans.** `DemoDataSeeder` is `@ConditionalOnProperty(name = "app.seed.demo-data", havingValue = "true")`; tests set it to false.

## 4. Try it yourself

Run from `backend/` with Docker running.

1. **See the mode validation fail.** In `SessionService.configureGeneralAdmission`, comment out the `if (!tiers.isEmpty())` check in the FLAT branch, then run:
   ```bash
   ./mvnw test -Dtest=AdminSessionTest#seatingAndPricingModesCannotBeMixed
   ```
   It fails because a FLAT session with tiers is now accepted with 201. Revert.
2. **See the 409 translation.** In `HallService.replaceLayout`, delete the `try/catch` around `seatRepository.deleteByHallId(id)` (keep the call). Run `./mvnw test -Dtest=AdminSessionTest#assignedSeatingGeneratesSessionSeatsWithPriceSnapshots`. It still passes, because the status is 409 either way: the FK violation reaches `GlobalExceptionHandler.handleDataIntegrity`. Add `.andDo(print())` to that `put(...)` call and you'll see the generic "conflicts with existing data" message instead of the specific one. That's the value of catching it in the service. Revert.
3. **Watch the N+1 avoidance.** Set `spring.jpa.show-sql: true` (it's on by default outside tests), start the app (`./mvnw spring-boot:run`) and call `curl "localhost:8080/api/v1/events/<id>/sessions"` for an event with several sessions. You'll see one `count(...) group by` query and one tier `IN` query, not one per session.
4. **Prove the admin gate covers new routes.** Add a throwaway `@RestController @RequestMapping("/api/v1/admin/tmp")` with one `@GetMapping` but *no* `@PreAuthorize`, then run `./mvnw test -Dtest=AdminAuthorizationGateTest`. `everyAdminControllerAlsoCarriesPreAuthorize` fails. Delete the controller.

## 5. Interview questions

**Q1. How do you stop an admin from creating a session that is both seated and general admission?**
`SessionService.create` branches on `seatingType` and validates the request shape for that mode: assigned needs a hall from the same location with seats and forbids pricingMode/capacity/tiers; GA forbids a hall and requires FLAT (capacity + price) or TIERED (≥1 uniquely named tier). The rules live in one service method, so the booking code can rely on them.

**Q2. Why copy hall seats into `session_seats` instead of booking `seats` directly?**
`Seat` is static layout shared by every showtime. A per-session row means locking a seat for the 7pm show never blocks the 10pm show, and each row carries its own status and a frozen price snapshot.

**Q3. Why is the seat price stored rather than computed?**
So it never changes retroactively. If the multiplier for RECLINER changes tomorrow, people already browsing tonight's show still see and pay the price that was published.

**Q4. How does the event list sort by date when `Event` has no date?**
Dates live on sessions. The search queries sessions, groups by event, and orders by `min(start_time)` over the sessions that match the filters. One query plus a count, then one `findAllById` for the page's events.

**Q5. How do you avoid N+1 queries when listing sessions?**
`toResponses` collects the session IDs and runs one grouped count of AVAILABLE seats and one `IN` query for tiers, then maps in memory. Associations needed in the response are fetched with `@EntityGraph`.

**Q6. What happens if an admin deletes a hall that has sessions?**
The `session_seats → seats → halls` foreign keys refuse the delete. The service catches `DataIntegrityViolationException` and returns a 409 with an explanation. I used the database constraint instead of a service-level check to avoid a circular dependency between the location and session domains. The trade-off is a less specific check.

**Q7. How are admin endpoints protected, and how do you know none were missed?**
Twice: a URL rule in the filter chain (`/api/v1/admin/** hasRole ADMIN`) and class-level `@PreAuthorize`. `AdminAuthorizationGateTest` enumerates all admin routes from `RequestMappingHandlerMapping` and asserts 401 for anonymous and 403 for USER on each, plus checks the annotation.

**Q8. Why seed demo data through services instead of SQL?**
The seed then obeys real validation and generates session seats and prices exactly as production does. Session dates are relative to today, so the demo never expires. It's gated by a property and only runs on an empty catalog.
