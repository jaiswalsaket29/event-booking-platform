-- Phase 5 (payments).
-- When the booking was confirmed; revenue-per-day reporting groups on it.
ALTER TABLE bookings ADD COLUMN confirmed_at TIMESTAMP;
CREATE INDEX idx_bookings_confirmed_at ON bookings(confirmed_at);

-- Gateway's reason for a failed attempt (shown to the user, e.g. "Card declined").
ALTER TABLE payments ADD COLUMN failure_reason VARCHAR(255);

-- Webhooks are matched (and de-duplicated) by the gateway's transaction id.
-- Postgres allows many NULLs in a unique index, so attempts without an id yet are fine.
CREATE UNIQUE INDEX uq_payments_transaction_id ON payments(transaction_id);
