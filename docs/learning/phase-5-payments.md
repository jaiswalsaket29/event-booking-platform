# Phase 5: Payments (learning notes)

Phase 5 turns a held booking into a paid one: idempotent payment attempts, a simulated gateway whose outcomes arrive by signed webhook, a state machine that can't move backward, a retry cap that releases tickets, and on confirmation a booking reference, a QR ticket and an email. Admins get a bookings search and dashboard stats. Code is under `backend/src/main/java/org/saket/eventbooking/`.

## 1. Request walkthroughs

### A. Paying: `POST /api/v1/bookings/{id}/payments`

Headers: `Authorization: Bearer ...`, `Idempotency-Key: 3f6c...` (one per checkout screen). Body: `{"paymentMethodToken": "tok_success"}`.

`payment/controller/PaymentController.pay` → `payment/service/PaymentService.pay`, which is deliberately **not** one transaction:

1. **Replay check.** `PaymentQueryService.findByIdempotencyKey`: if a payment with this key exists (same user and booking), return it with **200**. Same key for a different booking → 409.
2. **Open the attempt** (`PaymentAttemptService.open`, short transaction):
   - `BookingCheckoutService.lockForPayment`: `SELECT ... FROM bookings WHERE id = ? FOR NO KEY UPDATE`; 404 if not the caller's; 409 unless PENDING with a live Redis hold;
   - re-check the key **under the lock** (a same-key request may have committed while we waited);
   - 409 if another attempt is PENDING, or 3 attempts already exist (`app.payments.max-attempts`);
   - insert `Payment(PENDING, amount = booking.totalAmount, idempotencyKey)` with `saveAndFlush`. The unique index on `idempotency_key` is the backstop; `pay` turns a violation into a replay.
3. **Charge** `PaymentGateway.charge(...)` **outside any transaction**, so no row lock is held during a network call. The simulated gateway in its default WEBHOOK mode answers `PROCESSING` and schedules a signed webhook about 1.5s later.
4. **Record** `PaymentAttemptService.applyOutcome(paymentId, txnId, PROCESSING, ...)` stores the transaction id.
5. Response **201** with `status: PENDING`; the checkout screen polls `GET .../payments` or `GET /bookings/{id}`.

### B. The webhook settles it: `POST /api/v1/payments/webhook`

1. Public in `SecurityConfig` (no JWT); `PaymentWebhookController` takes the body as a raw `String`.
2. `PaymentWebhookService.handle`: `WebhookSignature.isValid` recomputes `sha256=HMAC-SHA256(rawBody, PAYMENT_WEBHOOK_SECRET)` and compares it in constant time. A bad signature gets 401 before anything is parsed.
3. Parse JSON, find the payment by `transactionId`, or by `paymentId` if the webhook beat step A.4 (404 otherwise, so a real provider retries).
4. `applyOutcome(..., SUCCEEDED, amount)` (one transaction):
   - lock the **booking row first**, then load the payment (fresh, after the lock);
   - payment already terminal → `DUPLICATE` (webhooks are delivered at least once);
   - amount ≠ payment amount → payment FAILED "Amount mismatch";
   - otherwise payment → SUCCESS, then `BookingCheckoutService.confirm`: booking → CONFIRMED, `confirmed_at`, `bookingReference = EVT-` + 12 random base32 chars, `SessionInventoryService.confirm` (seats LOCKED → BOOKED), Redis hold key removed **after commit**, `BookingConfirmedEvent` published;
   - if the booking had already ended (hold expired first): payment SUCCESS, booking unchanged, logged as `LATE_PAYMENT` and counted on the admin dashboard for a manual refund.
5. After commit, on another thread, `BookingConfirmationEmailer` (`@Async @TransactionalEventListener(AFTER_COMMIT)`) emails the tickets.

### C. Three declines

Each declined attempt sets the payment FAILED with a reason; the booking stays PENDING so the user can retry with a **new** key. On the third failure `PaymentAttemptService.fail` sees the cap reached and calls `BookingCheckoutService.endUnpaid(booking, FAILED)`, which releases the seats or capacity **in the same transaction**. If the hold expires instead, `BookingExpiryService` chooses FAILED (an attempt was made) or CANCELLED (none).

## 2. Key classes

| Class | Role |
|---|---|
| `booking/enums/BookingStatus`, `payment/enums/PaymentStatus` | The transition tables (`canTransitionTo`, `isTerminal`) |
| `Booking.transitionTo`, `Payment.transitionTo` | The only status mutators (setter removed); illegal moves throw `IllegalStatusTransitionException` (409, rollback) |
| `payment/gateway/PaymentGateway`, `SimulatedPaymentGateway`, `SimulatedWebhookSender` | Provider abstraction; deterministic test tokens; outcome via signed webhook |
| `payment/service/PaymentService` | Orchestrator: replay → open → charge → apply |
| `payment/service/PaymentAttemptService` | `open` and `applyOutcome`: the transactional, locked steps |
| `payment/service/PaymentQueryService` | Read-only facts for other domains (attempt counts, payment lists, late payments) |
| `payment/webhook/WebhookSignature`, `PaymentWebhookService` | HMAC verification and webhook handling |
| `booking/service/BookingCheckoutService` | Every way a PENDING booking ends: confirm, endUnpaid; lock helpers |
| `booking/service/BookingReferenceGenerator`, `BookingQrService` | Unguessable reference; zxing PNG at `GET /bookings/{id}/qr` |
| `booking/event/BookingConfirmationEmailer` | After-commit async email |
| `booking/service/BookingStatsService`, `booking/controller/AdminBookingController` | Admin search and dashboard stats |

## 3. Concepts used

- **Idempotency keys.** The client makes one key per checkout screen, so double clicks, browser retries and flaky networks all reuse it. The server does insert-or-fetch: look up first, check again under the booking lock, and keep the unique index as the final guard. `PaymentFlowTest.concurrentRequestsWithOneKeyCreateOnePayment` fires 12 identical requests at once and gets one payment. That test found a real race: before the under-lock check, a waiting request saw the first one's PENDING payment and said "in progress".
- **Don't hold locks across network calls.** The gateway call sits between two short transactions. Holding `FOR UPDATE` on the booking during an HTTP call would block expiry and other requests for as long as the provider takes.
- **Webhook as source of truth, and at-least-once delivery.** Providers retry until they get a 2xx, so the handler must be safe to repeat. Here idempotency falls out of the state machine: a terminal payment ignores further outcomes. The synchronous answer and the webhook can arrive in either order.
- **HMAC signatures.** The provider and we share a secret; the signature proves who sent the body and that it wasn't modified. Verify over the **raw bytes** (re-serialised JSON can differ), compare in constant time (`MessageDigest.isEqual`), and verify before parsing.
- **State machine on the data.** Terminal states are enforced by the entity, so no service, webhook or race can move CONFIRMED back to PENDING.
- **Consistent lock order.** Booking row first, then payment and inventory rows, in `open`, `applyOutcome`, and expiry. Payment success and hold expiry therefore serialize on the booking row, and exactly one of them wins.
- **After-commit side effects.** `@TransactionalEventListener(phase = AFTER_COMMIT)` means no email for a rolled-back confirmation. `@Async` takes it off the request thread (the test asserts it ran on a different thread). Redis clean-up uses `TransactionSynchronization.afterCommit` for the same reason.
- **Unknown outcomes.** If the gateway call throws, we don't know whether money moved, so the payment stays PENDING, and either the webhook settles it or the hold expires (booking FAILED). A success arriving after that is recorded truthfully and flagged for refund rather than silently dropped.
- **Reporting days.** Revenue per day is grouped in Java by `confirmed_at` converted to `Asia/Kolkata`, so the definition of "a day" lives in one place.

## 4. Try it yourself

Run from `backend/` with Docker running.

1. **Remove the under-lock key check** in `PaymentAttemptService.open` (the `findByIdempotencyKey(...).isPresent()` block) and run:
   ```bash
   ./mvnw test -Dtest=PaymentFlowTest#concurrentRequestsWithOneKeyCreateOnePayment
   ```
   It fails with "A payment for this booking is already in progress": the race described above. Revert.
2. **Make the webhook non-idempotent.** In `applyOutcome`, delete `if (payment.getStatus().isTerminal()) return Applied.DUPLICATE;` and run `./mvnw test -Dtest=PaymentWebhookTest#signedSuccessWebhookConfirmsAndDuplicatesAreNoOps`. The second delivery now tries `SUCCESS → SUCCESS`, which the state machine rejects with a 409. The state machine is the backstop. Revert.
3. **Watch a real webhook round trip.** Start compose and the app, log in as the admin, create a booking, then pay:
   ```bash
   curl -s -X POST localhost:8080/api/v1/bookings/<id>/payments -H "Authorization: Bearer <token>" -H "Idempotency-Key: try-it-001" -H "Content-Type: application/json" -d '{"paymentMethodToken":"tok_success"}'
   ```
   The response says PENDING. About 1.5s later `GET /api/v1/bookings/<id>` says CONFIRMED with an `EVT-...` reference, and the app log shows `Webhook evt_...` on a `scheduling-` thread followed by the dev email. Repeat the same curl: you get 200 and the same payment.
4. **Forge a webhook.** `curl -X POST localhost:8080/api/v1/payments/webhook -H "X-Webhook-Signature: sha256=00" -d '{}'` returns 401.

## 5. Interview questions

**Q1. How do you stop a double click from charging twice?**
The client sends an Idempotency-Key per checkout. The server returns the existing payment for a known key, re-checks under the booking row lock, and has a unique index as the last guard. The provider gets the same key too, so it de-duplicates on its side as well.

**Q2. Why isn't the payment one big transaction?**
The gateway call is a network round trip. Holding a row lock across it would block hold expiry and other requests, and a timeout would roll back our record of a charge that might have happened. Two short transactions around a lock-free call avoid both.

**Q3. Why trust the webhook over the API response?**
The user may close the tab and the synchronous call may time out; the provider's webhook is its authoritative record and it retries until acknowledged. Both paths go through one idempotent `applyOutcome`, so order doesn't matter.

**Q4. How do you secure the webhook endpoint?**
An HMAC-SHA256 signature over the raw body with a shared secret, checked in constant time before parsing. The amount is also checked against what we expected, and the payment is matched by the provider's transaction id (a unique index).

**Q5. What happens if the payment succeeds after the hold expired?**
Expiry and payment both lock the booking row, so one wins. If expiry won, the success is still recorded as SUCCESS (money really moved), the booking stays FAILED/CANCELLED, and the dashboard counts it as a late payment needing a refund. Refunds are out of scope, but the case is visible, not lost.

**Q6. How is the state machine enforced?**
Allowed transitions are defined on the status enums, and the entities expose `transitionTo` as the only mutator (the Lombok setter is removed). Anything else throws and rolls back. Services still check state first; the entity is the backstop.

**Q7. Why send the confirmation email after commit and asynchronously?**
After commit, so we never email about a confirmation that rolled back. Asynchronously, so a slow SMTP server can't slow the payment response or the webhook acknowledgement (which providers time out on).

**Q8. Why is the booking reference random rather than sequential?**
It's what the QR encodes, effectively the ticket. Sequential references are guessable; 60 random bits aren't. Crockford base32 avoids I/L/O/U so it can be read aloud or typed at the door.
