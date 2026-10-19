CREATE TABLE events (
    id            BIGSERIAL PRIMARY KEY,
    name          TEXT NOT NULL,
    total_tickets INT  NOT NULL CHECK (total_tickets > 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inventory (
    event_id  BIGINT PRIMARY KEY REFERENCES events(id),
    available INT    NOT NULL CHECK (available >= 0),   -- last line of defense against overselling
    version   BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE reservations (
    id              UUID PRIMARY KEY,
    event_id        BIGINT NOT NULL REFERENCES events(id),
    user_id         TEXT   NOT NULL,
    qty             INT    NOT NULL CHECK (qty > 0),
    status          TEXT   NOT NULL,
    idempotency_key TEXT   NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_reservations_event ON reservations(event_id);
CREATE INDEX idx_reservations_expiry ON reservations(status, expires_at);
