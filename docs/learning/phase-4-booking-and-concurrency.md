# Phase 4: Booking and concurrency (learning notes)

Phase 4 lets a signed-in, verified user start a checkout: the tickets are held for 10 minutes, nobody else can take them, and if nobody pays they go back on sale by themselves. Payment is Phase 5. Code is under `backend/src/main/java/org/saket/eventbooking/`.

## 1. Request walkthroughs

### A. Holding seats: `POST /api/v1/bookings`

```json
{"sessionId": "...", "sessionSeatIds": ["<seat B4>", "<seat B5>"]}
```

1. `JwtAuthenticationFilter` authenticates; `/api/v1/bookings/**` needs any logged-in user.
2. `booking/controller/BookingController.create` gets the user id via `@AuthenticationPrincipal UUID` and validates `CreateBookingRequest`.
3. `booking/service/BookingService.create` opens **one transaction** (`@Transactional`):
   1. `UserService.getById`, then reject with `ForbiddenException` (403) if `emailVerified` is false. It reads the DB, not the JWT claim, which may be stale.
   2. Ticket cap check (`app.booking.max-tickets-per-booking`).
   3. `session/service/SessionInventoryService.hold(sessionId, HoldRequest)`:
      - loads the session (with event/location/hall); 404 unless the event is PUBLISHED; 409 if the session isn't SCHEDULED or has started;
      - `strategyFor(session)` picks the one `SeatingStrategy` whose `supports(...)` is true;
      - `AssignedSeatingStrategy.hold` runs
        `select ... from session_seats where session_id = ? and id in (?, ?) order by id for no key update of ss1_0`.
        If another checkout holds a lock on one of those rows, this thread **waits** here. Once it has the locks, it checks that every seat is still AVAILABLE (409 otherwise), sets them LOCKED with `lockedAt`, and sums their prices.
   4. Saves a PENDING `Booking` and one `BookingSeat` per seat.
   5. `booking/hold/BookingHoldStore.place` runs `SET booking-hold:<bookingId> <bookingId> EX 600` in Redis.
4. Commit: the row locks are released, and the next waiting checkout re-reads the seats, sees LOCKED, and gets 409.
5. Response: `BookingResponse` with `status: PENDING`, the seats, `totalAmount`, and `holdExpiresAt`.

### B. Nobody pays: the hold expires

1. Ten minutes later Redis deletes the key and, because `notify-keyspace-events` contains `Ex`, publishes the key name on channel `__keyevent@0__:expired`.
2. `booking/hold/HoldExpiryListener.onMessage` (subscribed by `HoldExpirySubscription` on Boot's `RedisMessageListenerContainer`) parses the booking id and calls `BookingExpiryService.expire`.
3. `expire` (one transaction):
   - `select ... from bookings where id = ? for no key update`: **lock the booking row**;
   - if it isn't PENDING any more, do nothing and return false;
   - otherwise set CANCELLED and call `SessionInventoryService.release(...)`, which routes to the same strategy: seats LOCKED → AVAILABLE, or capacity counters back up.
4. If the app was down when the event fired, the event is gone. `StaleHoldSweeper.sweep` runs every 60s and expires PENDING bookings older than TTL + 30s through the same `expire` method.

## 2. Key classes

| Class | What it does | What breaks without it |
|---|---|---|
| `session/service/seating/SeatingStrategy` | One interface: `supports`, `hold`, `release` | Booking code fills up with `if (seatingType == ...)` branches |
| `FlatGeneralAdmissionStrategy` | Locks the session row (`EntityManager.refresh(session, PESSIMISTIC_WRITE)`), decrements `availableCapacity` | Overselling GA events |
| `TieredGeneralAdmissionStrategy` | Locks one tier row (`TicketTierRepository.lockByIdAndSessionId`), decrements its capacity | Overselling a tier |
| `AssignedSeatingStrategy` | Locks the chosen `session_seats` rows in id order, AVAILABLE → LOCKED | Two people holding the same seat; deadlocks |
| `session/service/SessionInventoryService` | Bookability checks + picks the strategy; `Propagation.MANDATORY` | Locks taken outside the booking transaction |
| `booking/service/BookingService` | Create (one transaction), list mine, get mine | — |
| `booking/hold/BookingHoldStore` | Redis key per hold (`SET ... EX`) | No timer, so abandoned checkouts hold seats forever |
| `booking/hold/HoldExpiryListener` + `HoldExpirySubscription` | Turns key-expired events into `expire` calls; ensures `notify-keyspace-events` has `Ex` | Holds would only expire on the sweeper's schedule |
| `booking/service/BookingExpiryService` | Lock booking → check PENDING → CANCELLED → release | Double releases (capacity grows past real) |
| `booking/hold/StaleHoldSweeper` | Fallback for lost events | A restart at the wrong moment leaves seats stuck |
| `db/migration/V7__...sql` | Index `bookings(status, created_at)` for the sweeper query | Sweeper scans the whole table |

## 3. Concepts used

- **Pessimistic locking (`@Lock(PESSIMISTIC_WRITE)`).** Postgres renders it as `SELECT ... FOR NO KEY UPDATE`. A second transaction asking for the same row waits until the first commits, then reads the committed value. That turns "check availability, then decrement" into a safe critical section. It's ideal when contention is the expected case (a rush for the last tickets): waiting beats failing and retrying.
- **Why `refresh` for the session row.** `SessionInventoryService` already loaded the session (without a lock). A locking JPQL query would lock the row but hand back the *already-managed* object with its old capacity, so two threads could both see "1 left". `entityManager.refresh(session, PESSIMISTIC_WRITE)` locks **and** re-reads. The concurrency test catches this class of bug.
- **Lock only what's contended.** The seat lock query doesn't `join fetch seat`; doing so would also lock the shared physical `seats` rows, making the 7pm and 10pm shows block each other. The tier lock query doesn't join the session either.
- **Deadlock avoidance by ordering.** User 1 wants A1+A2, user 2 wants A2+A1. Locking in request order can deadlock (each holds one, waits for the other). `ORDER BY ss.id` makes everyone lock in the same global order. `overlappingMultiSeatRequestsNeverDeadlockOrDoubleBook` hammers this. Across tables the rule is: booking row first, then inventory rows.
- **Transaction propagation.** `Propagation.MANDATORY` on `SessionInventoryService` throws `IllegalTransactionStateException` if there's no surrounding transaction (see `holdingOutsideATransactionIsRefused`). Locks are only meaningful if they last until the booking row commits.
- **Idempotency by state transition under a lock.** Expiry may be triggered by the listener, by the sweeper, and by several app instances at once. "Lock the booking, act only if PENDING" means exactly one caller releases (`concurrentExpiriesReleaseExactlyOnce`).
- **Redis as a timer, not as the source of truth.** The DB says what's held; Redis only says when to stop holding. Keyspace notifications are fire-and-forget pub/sub, hence the sweeper.
- **Atomicity across Postgres and Redis.** You can't have one transaction spanning both. The Redis `SET` runs last, inside the DB transaction: if Redis fails, the DB rolls back (no hold without a timer). If the DB commit fails after the `SET`, an orphan key expires later and `expire` finds nothing to do.
- **Ownership in the query.** `findByIdAndUserId` returns 404 for other users' bookings: no load-then-check gap, no existence leak.
- **Testing concurrency for real.** Threads released together by a `CountDownLatch`, distinct users, the real service and real Postgres. A mocked repository can't show locking.

## 4. Try it yourself

Run from `backend/` with Docker running.

1. **Remove the lock and watch overselling.** In `FlatGeneralAdmissionStrategy.lockAndRefresh`, change `entityManager.refresh(session, LockModeType.PESSIMISTIC_WRITE)` to `entityManager.refresh(session)`. Run:
   ```bash
   ./mvnw test -Dtest=BookingConcurrencyTest#lastFlatTicketIsSoldExactlyOnce+capacityIsNeverOversoldUnderLoad
   ```
   When this was written, 10 of 20 threads "bought" the single last ticket, and 30 of 30 got one of 10 tickets. Revert.
2. **See the deadlock protection.** In `SessionSeatRepository.lockBySessionIdAndIds`, remove `order by ss.id` and run `./mvnw test -Dtest=BookingConcurrencyTest#overlappingMultiSeatRequestsNeverDeadlockOrDoubleBook` a few times. Postgres usually returns rows in index order anyway, so it may still pass. That's the lesson: without the `ORDER BY`, correctness depends on an execution-plan detail you don't control. Revert.
3. **Watch a hold expire in the dev stack.** Start compose and the app, log in (`admin@eventbooking.dev` / `Admin@12345`), create a booking with curl, then:
   ```bash
   docker compose exec redis redis-cli ttl booking-hold:<bookingId>
   docker compose exec redis redis-cli expire booking-hold:<bookingId> 1
   ```
   A second later `GET /api/v1/bookings/<bookingId>` shows CANCELLED and the session's `ticketsAvailable` is back.
4. **Break idempotency.** In `BookingExpiryService.expire`, delete the `getStatus() != BookingStatus.PENDING` check and run `./mvnw test -Dtest=BookingExpiryTest#concurrentExpiriesReleaseExactlyOnce`. Capacity ends up above 10. Revert.

## 5. Interview questions

**Q1. How do you prevent two people from booking the last seat?**
The availability check and the update happen while holding a row lock (`SELECT ... FOR UPDATE` via `PESSIMISTIC_WRITE`) inside one transaction. The second buyer blocks on the lock, then sees the seat is LOCKED and gets a 409. A test fires 20 concurrent requests at one seat and asserts exactly one wins.

**Q2. Why pessimistic rather than optimistic locking?**
For hot inventory, conflicts are the normal case. With `@Version`, most of the buyers would fail at commit and need retries; with row locks they queue briefly and get a definite answer. Optimistic locking suits rarely-contended data like profile edits.

**Q3. How do you avoid deadlocks with multi-seat bookings?**
Every transaction locks seats in the same order (`ORDER BY id`), and across tables always booking row before inventory rows. Circular waits need inconsistent ordering, so they can't form.

**Q4. Why Redis for holds if the database already tracks LOCKED seats?**
The database records the state; Redis provides the timer. A TTL key plus keyspace notifications releases a hold within about a second of expiry without polling the database every few seconds.

**Q5. What if the app is down when the Redis key expires?**
Keyspace notifications aren't persisted, so that event is lost. A sweeper runs every minute and expires PENDING bookings older than TTL + grace. Both paths go through the same idempotent method, so it doesn't matter which one (or both) runs.

**Q6. How do you keep Redis and Postgres consistent without a distributed transaction?**
Order the operations so every failure mode is safe: the Redis `SET` happens last inside the DB transaction. Redis fails → DB rolls back. DB fails after the `SET` → an orphan key that expires harmlessly. Releases are idempotent, so duplicates do nothing.

**Q7. Why does the strategy code live in the session package if booking uses it?**
The strategies change session-domain rows (sessions, tiers, session seats), and the architecture rule says one domain never calls another's repositories. So the session domain exposes `SessionInventoryService`, which picks the strategy, and booking calls that. Booking still contains no seating-mode branching.

**Q8. Why does another user's booking return 404, not 403?**
403 would confirm the booking id exists. The owner check is in the query (`findByIdAndUserId`), so "not yours" and "doesn't exist" look the same.
