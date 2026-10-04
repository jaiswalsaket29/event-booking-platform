# Event booking platform — entity reference

This document captures the final data model, including the reasoning behind each decision. Use this as the source of truth when writing `@Entity` classes and migrations.

**ID strategy:** All primary keys are `UUID`, generated in application code via `@GeneratedValue(strategy = GenerationType.UUID)` (Hibernate 6+). This avoids ID enumeration attacks (IDOR), allows generating the ID before the transaction commits (needed for idempotency keys in the booking flow), and avoids collisions if the system ever splits into separate services.

**Enum mapping:** Every status/type field uses `@Enumerated(EnumType.STRING)`, never `ORDINAL` — ordinal mapping breaks silently if enum values are ever reordered or inserted mid-list.

**Fetch strategy:** All `@ManyToOne` and `@OneToOne` relationships are explicitly set to `FetchType.LAZY` (overriding JPA's default of `EAGER`). We fetch what's needed per-query using `JOIN FETCH` or entity graphs, to avoid the N+1 query problem.

---

## 1. User

Represents both regular users and admins — distinguished by role, not a separate table.

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| name | String | |
| email | String | unique, indexed — login identifier (not phone) |
| passwordHash | String | BCrypt hash, never plaintext; nullable — null for OAuth-only users |
| role | Enum (`USER`, `ADMIN`) | |
| phone | String | profile field only, not used for login |
| emailVerified | boolean | false by default for LOCAL signups; explicitly set true at creation for GOOGLE signups (Google already verifies email) |
| authProvider | Enum (`LOCAL`, `GOOGLE`) | |
| providerId | String | nullable — provider's subject/user ID, used to match repeat OAuth logins |
| createdAt | Instant | |

**Relationships:** One `User` has many `Booking` (mapped by `user` on the `Booking` side; entities default to unidirectional).

---

## 2. Location

The venue itself — a cinema complex, a stadium, a concert hall. Not tied to a specific event, since one venue hosts many events over time.

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| name | String | e.g. "PVR Phoenix Mall" |
| address | String | |
| city | String | indexed — used heavily for filtering |
| venueType | Enum (`ONLINE`, `OFFLINE`) | |

**Relationships:** One `Location` has many `Hall` (for offline, assigned-seating venues), and many `Session` reference it directly (for GA/online events with no hall structure).

---

## 3. Hall

A specific auditorium/screen/stage *within* a location. Static reference data — set up once, reused across hundreds of sessions. Only relevant for assigned-seating venues (a GA lawn concert has no `Hall`).

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| location | Location | `@ManyToOne`, LAZY |
| name | String | e.g. "Screen 4" |
| totalCapacity | int | |

**Relationships:** One `Hall` has many `Seat` (the physical seat layout, defined once).

**Why this exists:** Without `Hall`, a cinema's seat layout would have to be recreated separately for every showtime, and you couldn't query "what does Screen 4 look like" independent of any particular session.

---

## 4. Seat

The *physical* seat — static, defined once per hall, reused across every session shown in that hall.

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| hall | Hall | `@ManyToOne`, LAZY |
| rowLabel | String | e.g. "C" |
| seatNumber | int | |
| seatType | Enum (`REGULAR`, `PREMIUM`, `RECLINER`) | affects pricing |

**Relationships:** One `Seat` has many `SessionSeat` (its availability status differs per showtime).

---

## 5. Artist

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| name | String | |
| bio | String | |
| imageUrl | String | |
| genre | String | |

**Relationships:** Many-to-many with `Event`, via the `EventArtist` join entity.

---

## 6. Event

The "product" being sold — a movie, a concert tour, a comedy show. Does **not** hold location or time directly; those live on `Session`, since one event can run at multiple venues/times.

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| title | String | |
| description | String | |
| category | String | e.g. "Music", "Movie", "Comedy" |
| imageUrl | String | hero/thumbnail image |
| status | Enum (`DRAFT`, `PUBLISHED`, `CANCELLED`) | |
| language | String | e.g. "Hindi", "English" |
| durationMinutes | Integer | runtime / show length |
| ageRestriction | String | e.g. "U/A 13+", "18+" |
| highlights | Text | short bullet-style summary |
| termsAndConditions | Text | |
| isFeatured | boolean | drives homepage "featured events" |

**Relationships:** One `Event` has many `Session`. Many-to-many with `Artist` via `EventArtist`. One `Event` has many `EventImage` (gallery, beyond the single hero `imageUrl`).

---

## 6a. EventImage

Supports an image gallery per event — `Event.imageUrl` alone only covers the hero/thumbnail image.

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| event | Event | `@ManyToOne`, LAZY |
| imageUrl | String | |
| sortOrder | int | display order |

---

## 7. EventArtist (join entity)

Explicit join entity rather than a bare `@ManyToMany`, so extra fields (e.g. "headliner" vs "support act") can be attached without a schema rewrite.

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| event | Event | `@ManyToOne`, LAZY |
| artist | Artist | `@ManyToOne`, LAZY |
| role | String | optional, e.g. "headliner" |

---

## 8. Session

A specific showing/date of an event — this is what actually gets booked. Carries the seating strategy.

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| event | Event | `@ManyToOne`, LAZY |
| location | Location | `@ManyToOne`, LAZY |
| hall | Hall | `@ManyToOne`, LAZY, nullable (only for assigned seating) |
| startTime | Instant | indexed — used for date filtering |
| endTime | Instant | |
| seatingType | Enum (`GENERAL_ADMISSION`, `ASSIGNED_SEATING`) | drives which booking strategy applies |
| status | Enum (`SCHEDULED`, `CANCELLED`, `COMPLETED`) | independent of `Event.status` — one showtime can be cancelled while the rest of the event stays live |
| availableCapacity | Integer | nullable — only used when `seatingType = GENERAL_ADMISSION` |
| basePrice | BigDecimal | |
| pricingMode | Enum (`FLAT`, `TIERED`) | nullable — null for `ASSIGNED_SEATING`; required for `GENERAL_ADMISSION`. Declares which capacity-tracking mode applies, checked against `availableCapacity`/`TicketTier` presence at session-creation time (service-layer validation, not a DB constraint) |

**Relationships:** One `Session` has many `SessionSeat` (only if assigned seating) and many `Booking`.

---

## 9. TicketTier

Optional pricing tiers for a GA session (e.g. "Standard", "VIP", "Group of 4"). A GA `Session` operates in exactly one of two modes: flat capacity (`Session.availableCapacity`) *or* tiered capacity (one-to-many `TicketTier` rows) — never both. The booking service checks which mode a session is in and decrements the correct counter. This is a third `SeatingStrategy` implementation alongside flat GA and assigned seating.

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| session | Session | `@ManyToOne`, LAZY |
| name | String | e.g. "VIP", "Group of 4" |
| price | BigDecimal | |
| totalCapacity | int | |
| availableCapacity | int | decremented atomically on booking |

**Relationships:** One `TicketTier` has many `Booking` (nullable FK on `Booking`, populated only when the session uses tiered pricing).

---

## 10. SessionSeat

The transactional counterpart to `Seat` — one row per physical seat, *per session*. This is the row that actually gets locked during booking, not `Seat` itself, so locking a seat for one showtime never blocks bookings for a different showtime in the same hall.

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| session | Session | `@ManyToOne`, LAZY |
| seat | Seat | `@ManyToOne`, LAZY |
| status | Enum (`AVAILABLE`, `LOCKED`, `BOOKED`) | |
| lockedAt | Instant | nullable — set when status becomes `LOCKED`; used for TTL/expiry so an abandoned checkout doesn't lock a seat forever. Lock enforcement lives in Redis (TTL key), with this column reflecting the resulting state — see Phase 4. |
| price | BigDecimal | this seat's price for this session — set from `seatType` at session-creation time, doesn't change retroactively if base prices change |

**Unique constraint:** (`session_id`, `seat_id`) — a seat can only have one status row per session.

---

## 11. Booking

The user-facing reservation record. Works identically regardless of seating strategy — it just stores what was reserved.

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| user | User | `@ManyToOne`, LAZY |
| session | Session | `@ManyToOne`, LAZY |
| ticketTier | TicketTier | `@ManyToOne`, LAZY, nullable — only when session uses tiered pricing |
| status | Enum (`PENDING`, `CONFIRMED`, `CANCELLED`, `FAILED`) | |
| quantity | Integer | used for GA/tiered bookings (not assigned seating) |
| totalAmount | BigDecimal | |
| bookingReference | String | human-readable, e.g. "EVT-8X2K9P"; nullable, set only at `PENDING → CONFIRMED` |
| createdAt | Instant | |

**Relationships:** One `Booking` has many `BookingSeat` (assigned seating only) and many `Payment` attempts.

---

## 12. BookingSeat (join entity)

Links a `Booking` to the specific `SessionSeat`(s) reserved. Only populated for assigned-seating bookings; GA bookings use `Booking.quantity`. Rows are append-only; unique on (`booking_id`, `session_seat_id`).

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| booking | Booking | `@ManyToOne`, LAZY |
| sessionSeat | SessionSeat | `@ManyToOne`, LAZY |

---

## 13. Payment

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| booking | Booking | `@ManyToOne`, LAZY *(see note)* |
| amount | BigDecimal | |
| status | Enum (`PENDING`, `SUCCESS`, `FAILED`, `REFUNDED`) | |
| transactionId | String | from the payment gateway |
| idempotencyKey | String | unique, indexed — prevents double-charging on retry; client-generated |
| paidAt | Instant | nullable until success |

> **Note:** `@ManyToOne` rather than `@OneToOne`, because a booking can have multiple payment *attempts* if an earlier one fails. Query "the latest successful payment" rather than assuming exactly one payment per booking.

---

## 14. PasswordResetToken

Backs the "forgot password" flow. Short-lived, single-use — never email a new password directly.

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| user | User | `@ManyToOne`, LAZY |
| token | String | unique, indexed |
| expiresAt | Instant | short-lived, e.g. 15–30 min |
| used | boolean | invalidated after first use |

---

## 14a. EmailVerificationToken

Backs email verification for `LOCAL` signups. Structurally identical to `PasswordResetToken`, but kept as an independent entity rather than sharing a `@MappedSuperclass` (see `project-context.md` Day 5 decisions).

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| user | User | `@ManyToOne`, LAZY |
| token | String | unique, indexed |
| expiresAt | Instant | short-lived |
| used | boolean | invalidated after first use |

---

## 14b. RefreshToken (added Day 8, migration V5)

DB-backed so logout/revocation is real. Stored as a SHA-256 hash, never raw.

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| user | User | `@ManyToOne`, LAZY |
| tokenHash | String | unique, indexed |
| expiresAt | Instant | default 30 days |
| revoked | boolean | set on rotation and logout |
| createdAt | Instant | |

---

## 15. ContactMessage

Backs the Contact Us page, stored so admins can manage submissions from the dashboard.

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| name | String | |
| email | String | |
| subject | String | |
| message | Text | |
| status | Enum (`NEW`, `RESOLVED`) | for admin triage; DB default `NEW` |
| createdAt | Instant | |

---

## Relationship summary

- `User` 1 → * `Booking`
- `User` 1 → * `PasswordResetToken`
- `User` 1 → * `EmailVerificationToken`
- `User` 1 → * `RefreshToken`
- `Location` 1 → * `Hall`
- `Location` 1 → * `Session` (GA sessions reference location directly, no hall)
- `Hall` 1 → * `Seat`
- `Seat` 1 → * `SessionSeat`
- `Event` 1 → * `Session`
- `Event` 1 → * `EventImage`
- `Event` * ↔ * `Artist` (via `EventArtist`)
- `Session` 1 → * `SessionSeat`
- `Session` 1 → * `TicketTier` (only when using tiered GA pricing)
- `Session` 1 → * `Booking`
- `TicketTier` 1 → * `Booking`
- `Booking` 1 → * `BookingSeat`
- `SessionSeat` 1 → * `BookingSeat`
- `Booking` 1 → * `Payment`

## Enums reference

| Enum | Values |
|---|---|
| `Role` | `USER`, `ADMIN` |
| `VenueType` | `ONLINE`, `OFFLINE` |
| `SeatType` | `REGULAR`, `PREMIUM`, `RECLINER` |
| `EventStatus` | `DRAFT`, `PUBLISHED`, `CANCELLED` |
| `SeatingType` | `GENERAL_ADMISSION`, `ASSIGNED_SEATING` |
| `SessionSeatStatus` | `AVAILABLE`, `LOCKED`, `BOOKED` |
| `BookingStatus` | `PENDING`, `CONFIRMED`, `CANCELLED`, `FAILED` |
| `PaymentStatus` | `PENDING`, `SUCCESS`, `FAILED`, `REFUNDED` |
| `ContactMessageStatus` | `NEW`, `RESOLVED` |
| `AuthProvider` | `LOCAL`, `GOOGLE` |
| `SessionStatus` | `SCHEDULED`, `CANCELLED`, `COMPLETED` |
| `PricingMode` | `FLAT`, `TIERED` |
