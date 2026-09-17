CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(id),
    location_id UUID NOT NULL REFERENCES locations(id),
    hall_id UUID REFERENCES halls(id),
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    seating_type VARCHAR(20) NOT NULL,
    pricing_mode VARCHAR(20),
    status VARCHAR(20) NOT NULL,
    available_capacity INT,
    base_price NUMERIC(10,2) NOT NULL
);

CREATE INDEX idx_sessions_event ON sessions(event_id);
CREATE INDEX idx_sessions_start_time ON sessions(start_time);

CREATE TABLE ticket_tiers (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES sessions(id),
    name VARCHAR(255) NOT NULL,
    price NUMERIC(10,2) NOT NULL,
    total_capacity INT NOT NULL,
    available_capacity INT NOT NULL
);

CREATE INDEX idx_ticket_tiers_session ON ticket_tiers(session_id);

CREATE TABLE session_seats (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES sessions(id),
    seat_id UUID NOT NULL REFERENCES seats(id),
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    locked_at TIMESTAMP,
    price NUMERIC(10,2) NOT NULL,
    UNIQUE (session_id, seat_id)
);

CREATE INDEX idx_session_seats_session ON session_seats(session_id);