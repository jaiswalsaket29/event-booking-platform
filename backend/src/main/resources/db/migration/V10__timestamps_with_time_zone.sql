-- Store every instant as timestamptz (an absolute point in time) instead of a zone-less wall-clock
-- timestamp. Until now the meaning of a stored value depended on the writer's session time zone: the app
-- pinned its JVM (and so its JDBC session) to Asia/Kolkata, tests ran in UTC, and DEFAULT now() used
-- whatever the session said.
--
-- Existing rows were written by the app or Flyway with an Asia/Kolkata session, so their wall-clock
-- values are interpreted in that zone. (A database written only by the UTC test JVM is always created
-- fresh by Testcontainers, so it never has rows here.)

ALTER TABLE users
    ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'Asia/Kolkata';

ALTER TABLE sessions
    ALTER COLUMN start_time TYPE timestamptz USING start_time AT TIME ZONE 'Asia/Kolkata',
    ALTER COLUMN end_time   TYPE timestamptz USING end_time   AT TIME ZONE 'Asia/Kolkata';

ALTER TABLE session_seats
    ALTER COLUMN locked_at TYPE timestamptz USING locked_at AT TIME ZONE 'Asia/Kolkata';

ALTER TABLE bookings
    ALTER COLUMN created_at   TYPE timestamptz USING created_at   AT TIME ZONE 'Asia/Kolkata',
    ALTER COLUMN confirmed_at TYPE timestamptz USING confirmed_at AT TIME ZONE 'Asia/Kolkata';

ALTER TABLE payments
    ALTER COLUMN paid_at    TYPE timestamptz USING paid_at    AT TIME ZONE 'Asia/Kolkata',
    ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'Asia/Kolkata';

ALTER TABLE password_reset_tokens
    ALTER COLUMN expires_at TYPE timestamptz USING expires_at AT TIME ZONE 'Asia/Kolkata';

ALTER TABLE email_verification_tokens
    ALTER COLUMN expires_at TYPE timestamptz USING expires_at AT TIME ZONE 'Asia/Kolkata';

ALTER TABLE contact_messages
    ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'Asia/Kolkata';

ALTER TABLE refresh_tokens
    ALTER COLUMN expires_at TYPE timestamptz USING expires_at AT TIME ZONE 'Asia/Kolkata',
    ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'Asia/Kolkata';
