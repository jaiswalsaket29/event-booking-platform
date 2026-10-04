# Phase 1: Data model (learning notes)

Backfilled. Phase 1 created the schema (Flyway V1–V4) and the 16 JPA entities. Field-level detail is in `docs/entity-reference.md`.

## 1. Walkthrough: how a table and an entity stay in sync

1. A migration, e.g. `V3__add_sessions_ticket_tiers_session_seats.sql`, creates `sessions`, `ticket_tiers` and `session_seats` with FKs, a unique `(session_id, seat_id)` and indexes (`idx_sessions_start_time`).
2. The matching entity, `session/entity/SessionSeat`, maps those columns: `@Id @GeneratedValue(strategy = GenerationType.UUID)`, `@ManyToOne(fetch = LAZY) @JoinColumn(name = "session_id")`, `@Enumerated(EnumType.STRING) status`, and a `@UniqueConstraint` mirroring the DB one.
3. At startup Flyway applies V1..Vn in order and records each in `flyway_schema_history` with a checksum. Hibernate then validates (`ddl-auto: validate`): a missing column or wrong type fails startup instead of failing at runtime.
4. `UserRepositoryTest` (`@DataJpaTest` + Testcontainers `postgres:16` + `@ServiceConnection`) proves the whole migration chain applies on a fresh database and a `User` round-trips.

## 2. Key pieces

| Piece | Notes |
|---|---|
| `V1__init_schema.sql` | `users`, `locations` (indexed `city`), `halls` |
| `V2__add_seat_artist_event_batch.sql` | `seats`, `artists`, `events`, `event_images` (`ON DELETE CASCADE`), `event_artists` (unique pair) |
| `V3__...sql` | `sessions`, `ticket_tiers`, `session_seats` |
| `V4__...sql` | `bookings`, `booking_seats`, `payments` (unique `idempotency_key`), token tables, `contact_messages` |
| Entities | Package-by-feature: `user`, `location`, `event`, `session`, `booking`, `payment`, `contact`, each with `entity/` and `enums/` |
| `UserRepository` | First Spring Data repository (`findByEmail`, `existsByEmail`) |

The domain chain: `Location → Hall → Seat` (static venue) and `Event → Session → SessionSeat / TicketTier → Booking → BookingSeat / Payment`.

## 3. Concepts used

- **UUID primary keys.** Non-guessable IDs (no IDOR by incrementing `/bookings/42`), IDs available before commit, no collisions if data is ever merged or split.
- **`EnumType.STRING`.** `ORDINAL` stores 0/1/2 and silently corrupts data if enum constants are reordered.
- **`FetchType.LAZY` on every `@ManyToOne`.** JPA defaults `@ManyToOne` to EAGER, which pulls in object graphs you didn't ask for and causes N+1 queries. Fetch per query with `JOIN FETCH` or `@EntityGraph` instead.
- **Unidirectional relationships.** Only the `@ManyToOne` side is mapped. "Halls of a location" is a repository query (`HallRepository.findByLocationIdOrderByName`), not a `Location.halls` collection.
- **Explicit join entities** (`EventArtist`, `BookingSeat`) instead of `@ManyToMany`, so extra columns (like `role`) can be added later.
- **Flyway owns the schema.** Hibernate never alters tables. Applied migrations are never edited (the checksum would fail); every change is a new `V<n>__`.
- **`Session` vs `SessionSeat`.** `Seat` is the physical chair; `SessionSeat` is that chair for one showtime, with its own status, `lockedAt` and price snapshot. Locking one show's seat never blocks another show.
- **`Payment` is `@ManyToOne` to `Booking`.** Multiple attempts per booking (retries), not one-to-one.
- **No `@Data` on entities.** Lombok `@Data` generates `equals/hashCode/toString` over all fields, which can trigger lazy loading and break in hash sets. Entities use `@Getter @Setter @NoArgsConstructor @AllArgsConstructor`.

## 4. Try it yourself

1. Add a field `private String foo;` to `Location` without a migration and run `./mvnw test -Dtest=UserRepositoryTest`: the context fails with a schema-validation error naming the missing column. Remove it.
2. Change one character in `V1__init_schema.sql` and run the app against your compose DB: Flyway refuses with a checksum mismatch. Revert with `git checkout -- backend/src/main/resources/db/migration/V1__init_schema.sql`.
3. In psql (`docker compose exec postgres psql -U eventbooking`), run `select * from flyway_schema_history;` to see the migration ledger.

## 5. Interview questions

**Q1. Why UUIDs instead of auto-increment IDs?**
They aren't enumerable (IDOR protection), can be generated in the app before insert, and don't collide across systems. Trade-offs: 16 bytes instead of 8 and less index locality.

**Q2. Why `ddl-auto: validate` instead of `update`?**
Schema changes must be reviewed, versioned and repeatable across environments. `update` can't drop or rename safely and drifts silently. Flyway gives an auditable history; `validate` catches entity/schema mismatches at startup.

**Q3. Why not use `@ManyToMany` for events and artists?**
A join entity lets the relationship carry data (`role`: headliner/support) and keeps queries explicit.

**Q4. Why separate `Seat` and `SessionSeat`?**
Layout is defined once per hall; availability and price are per showtime. Locking happens on `SessionSeat` rows, so concurrent bookings for different shows never contend.

**Q5. How do you avoid N+1 with all-LAZY associations?**
Fetch what each use case needs: `JOIN FETCH` in JPQL or `@EntityGraph` on repository methods, and batched `IN` queries for lists.
