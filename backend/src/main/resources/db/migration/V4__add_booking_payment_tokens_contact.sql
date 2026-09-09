-- Booking: the core reservation record
CREATE TABLE bookings (
                          id UUID PRIMARY KEY,
                          user_id UUID NOT NULL REFERENCES users(id),
                          session_id UUID NOT NULL REFERENCES sessions(id),
                          ticket_tier_id UUID REFERENCES ticket_tiers(id), -- nullable: only set when session uses tiered pricing
                          status VARCHAR(20) NOT NULL,
                          quantity INT, -- used for GA/tiered bookings, not assigned seating
                          total_amount NUMERIC(10, 2) NOT NULL,
                          booking_reference VARCHAR(20) UNIQUE, -- nullable: set only at PENDING -> CONFIRMED
                          created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_bookings_user_id ON bookings(user_id);
CREATE INDEX idx_bookings_session_id ON bookings(session_id);

-- BookingSeat: join entity, assigned-seating bookings only
CREATE TABLE booking_seats (
                               id UUID PRIMARY KEY,
                               booking_id UUID NOT NULL REFERENCES bookings(id),
                               session_seat_id UUID NOT NULL REFERENCES session_seats(id),
                               CONSTRAINT uq_booking_seats_booking_session_seat UNIQUE (booking_id, session_seat_id)
);

-- Payment: multiple attempts per booking allowed (many-to-one, not one-to-one)
CREATE TABLE payments (
                          id UUID PRIMARY KEY,
                          booking_id UUID NOT NULL REFERENCES bookings(id),
                          amount NUMERIC(10, 2) NOT NULL,
                          status VARCHAR(20) NOT NULL,
                          transaction_id VARCHAR(255), -- from the payment gateway
                          idempotency_key VARCHAR(255) NOT NULL UNIQUE, -- client-generated, set at row creation
                          paid_at TIMESTAMP -- nullable until status = SUCCESS
);

CREATE INDEX idx_payments_booking_id ON payments(booking_id);

-- PasswordResetToken
CREATE TABLE password_reset_tokens (
                                       id UUID PRIMARY KEY,
                                       user_id UUID NOT NULL REFERENCES users(id),
                                       token VARCHAR(255) NOT NULL UNIQUE,
                                       expires_at TIMESTAMP NOT NULL,
                                       used BOOLEAN NOT NULL DEFAULT FALSE
);

-- EmailVerificationToken: structurally identical to PasswordResetToken,
-- kept as a separate table deliberately (see entity-reference.md notes)
CREATE TABLE email_verification_tokens (
                                           id UUID PRIMARY KEY,
                                           user_id UUID NOT NULL REFERENCES users(id),
                                           token VARCHAR(255) NOT NULL UNIQUE,
                                           expires_at TIMESTAMP NOT NULL,
                                           used BOOLEAN NOT NULL DEFAULT FALSE
);

-- ContactMessage: standalone, no FK
CREATE TABLE contact_messages (
                                  id UUID PRIMARY KEY,
                                  name VARCHAR(255) NOT NULL,
                                  email VARCHAR(255) NOT NULL,
                                  subject VARCHAR(255),
                                  message TEXT NOT NULL,
                                  status VARCHAR(20) NOT NULL DEFAULT 'NEW',
                                  created_at TIMESTAMP NOT NULL DEFAULT now()
);