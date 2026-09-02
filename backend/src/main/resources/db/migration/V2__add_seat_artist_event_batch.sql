-- Seats: static reference data, one row per physical seat in a hall.
-- Only relevant for offline/assigned-seating venues.
CREATE TABLE seats (
    id UUID PRIMARY KEY,
    hall_id UUID NOT NULL
    REFERENCES halls(id)
    ON DELETE RESTRICT,
    row_label VARCHAR(10) NOT NULL,
    seat_number INT NOT NULL,
    seat_type VARCHAR(20) NOT NULL
);

CREATE INDEX idx_seats_hall_id ON seats(hall_id);

-- Artists: independent entity, linked to events via EventArtist.
CREATE TABLE artists (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    bio TEXT,
    image_url VARCHAR(500),
    genre VARCHAR(100)
);

-- Events: the "product" being sold. No location/time here — that lives on Session.
CREATE TABLE events (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    category VARCHAR(100) NOT NULL,
    image_url VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    language VARCHAR(50),
    duration_minutes INT,
    age_restriction VARCHAR(20),
    highlights TEXT,
    terms_and_conditions TEXT,
    is_featured BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_events_status ON events(status);
CREATE INDEX idx_events_category ON events(category);

-- EventImage: gallery images per event, beyond the single hero imageUrl on Event.
CREATE TABLE event_images (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    image_url VARCHAR(500) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_event_images_event_id ON event_images(event_id);

-- EventArtist: explicit join entity (not bare many-to-many) so we can attach
-- fields like `role` later without a schema rewrite.
CREATE TABLE event_artists (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    artist_id UUID NOT NULL REFERENCES artists(id) ON DELETE RESTRICT,
    role VARCHAR(100),
    CONSTRAINT uq_event_artist UNIQUE (event_id, artist_id)
);

CREATE INDEX idx_event_artists_event_id ON event_artists(event_id);
CREATE INDEX idx_event_artists_artist_id ON event_artists(artist_id);