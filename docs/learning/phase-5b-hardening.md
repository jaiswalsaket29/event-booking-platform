# Phase 5b: Hardening (learning notes)

Phase 5b closed the gaps found in a review after Phase 5. Each fix is small, but most of them are about the same theme: **what happens when two things happen at once**, or when something you didn't expect is still in the persistence context. Two real bugs were found by the new tests along the way. Code is under `backend/src/main/java/org/saket/eventbooking/`.

## 1. Request walkthroughs

### A. Admin cancels a session that has sold tickets

`PUT /api/v1/admin/sessions/{id}` with `"status": "CANCELLED"`:

1. `session/service/SessionService.update` (one `@Transactional`) refuses reopening a cancelled session or cancelling a completed one, then calls the private `cancel(session)`.
2. `cancel` sets CANCELLED and **publishes** `session/event/SessionCancelledEvent`. Spring calls synchronous `@EventListener`s right there, on the same thread and in the same transaction.
3. `booking/event/SessionCancellationListener.onSessionCancelled` (`Propagation.MANDATORY`):
   - `findIdsBySessionIdAndStatusIn(session, [PENDING, CONFIRMED])`, ordered by id;
   - for each: `BookingCheckoutService.lock` (`SELECT ... FOR NO KEY UPDATE`), re-check the status, then:
     - PENDING → `endUnpaid(booking, CANCELLED)`: release the seats or capacity, remove the Redis key after commit;
     - CONFIRMED → `cancelConfirmed`: the CONFIRMED → CANCELLED transition (allowed only here), seats stay BOOKED, publish `BookingCancelledByOrganiserEvent`.
4. Commit. Then `BookingCancellationEmailer` (`@Async @TransactionalEventListener(AFTER_COMMIT)`) emails each ticket holder.
5. The paid booking's SUCCESS payment now shows up in `paymentsNeedingRefund` and in `GET /admin/bookings?needsAttention=true`.

Cancelling an **event** adds one hop in front: `EventService.update` publishes `event/event/EventCancelledEvent`, and `SessionService.onEventCancelled` cancels each SCHEDULED session the same way.

### B. The hold expires while the customer is paying

1. Checkout opens a payment (PENDING) at 9:58 into a 10-minute hold; the webhook will arrive in a few seconds.
2. At 10:00 Redis expires the hold key → `BookingExpiryService.expire` locks the booking, sees a PENDING payment younger than `app.booking.payment-grace` (2m), logs "deferred" and **returns without changing anything**.
3. The webhook arrives → `applyOutcome` → CONFIRMED. The customer keeps their seats.
4. If the payment never resolves, the sweeper (every 60s) retries the expiry; once the payment is older than the grace period, the booking FAILS and the payment is counted as "stuck" for an admin to check.

### C. Two tabs refresh the same token

`POST /api/v1/auth/refresh` → `RefreshTokenService.rotate` (one transaction): `findByTokenHashForUpdate` locks the token row. The first request revokes it and issues a new pair; the second waits on the lock, then sees `revoked = true` → 401.

## 2. Key classes

| Class | Role |
|---|---|
| `session/event/SessionCancelledEvent`, `event/event/EventCancelledEvent` | Domain events that start the cascade without the publisher knowing who listens |
| `booking/event/SessionCancellationListener` | Ends a cancelled session's bookings, row by row, in the same transaction |
| `booking/event/BookingCancellationEmailer` | After-commit async email to ticket holders |
| `BookingCheckoutService.cancelConfirmed`, `lockOwn` | Organiser cancellation of a paid booking; owner-checked locking |
| `BookingService.cancelMine` + `POST /bookings/{id}/cancel` | User abandons checkout; same `endUnpaid` path as expiry |
| `BookingExpiryService.expire` | Now defers while a recent payment is in flight |
| `RefreshTokenService.rotate` | Atomic, locked rotation (replaces `validateAndRevoke`) |
| `PaymentQueryService.countPaymentsNeedingRefund` / `countStuckPendingPayments` | Dashboard counts; `needsAttention` filter in `BookingService.adminSearch` |
| `FlatGeneralAdmissionStrategy.lockAndRefresh` | Now `flush()` before `refresh(..., PESSIMISTIC_WRITE)` |

## 3. Concepts used

- **Synchronous domain events for decoupling inside one transaction.** `publishEvent` + `@EventListener` runs the listener immediately, in the caller's transaction. You get loose coupling (session code doesn't import booking code, and there's no `EventService` ↔ `SessionService` bean cycle) without giving up atomicity. Contrast with `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`, used for emails: those run only if the transaction committed, on another thread.
- **`refresh()` discards unflushed changes.** `EntityManager.refresh(entity)` overwrites the managed object with the database row. If the current transaction changed that entity but hasn't flushed yet, the change is lost silently. The cascade test caught exactly this: the session was set to CANCELLED, releasing a flat-GA hold refreshed the session row, and the status went back to SCHEDULED. Flushing first writes our own change to the DB (inside our transaction), so the refresh reads it back.
- **Open-in-view off.** With OSIV on, the Hibernate session stays open for the whole HTTP request, so lazy loading "just works" in controllers, holding a DB connection while rendering and hiding where queries really happen. Turning it off made the refresh endpoint fail with `LazyInitializationException`, which exposed the non-atomic rotation.
- **Compare-and-set with a row lock.** Token rotation is "check unused, mark used" and must happen once. A `FOR UPDATE` lock on the token row turns the check and the write into one atomic step.
- **Bounded waiting instead of racing.** The payment grace trades a slightly longer hold (bounded by grace plus one sweep) for never failing a booking whose money is about to arrive.
- **Make the unresolvable visible.** Stuck and refund-owed payments aren't fixed automatically; guessing would be worse. They become a count and a filter on the admin dashboard.
- **LRU via `LinkedHashMap`.** `new LinkedHashMap<>(16, 0.75f, true)` with `removeEldestEntry` gives a least-recently-used cache in a few lines (wrapped in `Collections.synchronizedMap` for thread safety).

## 4. Try it yourself

Run from `backend/` with Docker running.

1. **See the refresh bug.** In `FlatGeneralAdmissionStrategy.lockAndRefresh`, delete `entityManager.flush();` and run:
   ```bash
   ./mvnw test -Dtest=CancellationCascadeTest#aPaymentLandingAfterTheCancellationBecomesARefundNotATicket
   ```
   It fails with `expected:<CANCELLED> but was:<SCHEDULED>`. Revert.
2. **See what OSIV was hiding.** Read the old code with `git show 2e06bf7`: the controller called `validateAndRevoke`, which returned the token's lazy `User`, and then used `user.getEmail()` outside any transaction. When this phase was built, that request returned 500 (`LazyInitializationException`) as soon as `open-in-view` was false; with OSIV on, it silently ran an extra query in the controller.
3. **Break rotation atomicity.** In `RefreshTokenRepository.findByTokenHashForUpdate`, remove the `@Lock` annotation and run `./mvnw test -Dtest=RefreshTokenTest#concurrentRefreshesOfOneTokenRotateExactlyOnce` a few times: more than one refresh can succeed. Revert.
4. **Watch the deferral.** Run `./mvnw test -Dtest=PaymentWebhookTest#expiryWaitsForAnInFlightPaymentAndTheWebhookStillConfirms` and look for "Hold expiry for booking ... deferred: a payment is in flight" in the output.

## 5. Interview questions

**Q1. What happens to tickets when an organiser cancels a show?**
In the cancelling transaction, every open checkout is cancelled and its hold released, and every confirmed booking becomes CANCELLED. Seats stay BOOKED as the record of what was sold, the customer is emailed after commit, and the payment appears in the admin refund queue. Holds and payments require a scheduled session, so nothing new can be sold. A payment that lands later becomes a refund, never a ticket.

**Q2. Why use events rather than calling the booking service from the session service?**
Session code shouldn't know about bookings, and `EventService` → `SessionService` → `EventService` would be a dependency cycle. Synchronous events keep the domains decoupled while still running in one transaction, so the cascade is all-or-nothing.

**Q3. Tell me about a bug your tests caught.**
A locking `refresh()` on the session discarded the transaction's own unflushed status change, so a cancelled flat-GA session came back as SCHEDULED. It only showed for flat GA because that strategy refreshes the session row; assigned seating doesn't. The fix was to flush before refresh, documented where it happens.

**Q4. Why turn off open-in-view?**
It keeps the persistence context open for the whole request, so lazy loads in controllers or serialization silently run queries outside transactions and hold DB connections longer. Turning it off makes those fail loudly in tests. It immediately exposed a controller depending on it.

**Q5. How do you stop a hold timer from failing a booking that's being paid?**
Expiry checks, under the booking lock, for a payment opened within a grace period that's still PENDING, and if so leaves the booking alone; the sweeper retries. It's bounded, so a payment that never resolves still ends the hold, and it's then flagged as stuck.

**Q6. Two browser tabs refresh the same token at the same moment. What happens?**
The token row is locked during rotation. One request revokes it and gets a new pair; the other waits, sees it revoked, and gets 401. A test runs eight concurrent refreshes and checks exactly one succeeds.
