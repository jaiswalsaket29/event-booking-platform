-- Supports the stale-hold sweeper: "PENDING bookings created before <cutoff>".
CREATE INDEX idx_bookings_status_created_at ON bookings(status, created_at);
