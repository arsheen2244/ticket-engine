-- User accounts for the web app. Passwords are stored as BCrypt hashes, never in plain text.
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         TEXT NOT NULL UNIQUE,              -- stored lower-cased
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- "My orders" looks reservations up by user.
CREATE INDEX idx_reservations_user ON reservations(user_id, created_at DESC);
