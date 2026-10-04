-- RefreshToken: DB-backed so logout/revocation is real, not just client-side token deletion.
-- Token is stored as a SHA-256 hash, not raw — a longer-lived credential than
-- PasswordResetToken/EmailVerificationToken, so a DB leak shouldn't hand out usable tokens directly.
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);