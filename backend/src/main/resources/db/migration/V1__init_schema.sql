CREATE TABLE users(
    id UUID PRIMARY KEY ,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(100) NOT NULL UNIQUE ,
    password_Hash VARCHAR(255),
    role VARCHAR(20) NOT NULL ,
    phone VARCHAR(20),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    provider_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);



CREATE TABLE locations(
    id UUID PRIMARY KEY ,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(500),
    city VARCHAR(255) NOT NULL,
    venue_type VARCHAR(20) NOT NULL

);

CREATE INDEX idx_locations_city on locations(city);

CREATE TABLE halls (
    id UUID PRIMARY KEY,
    location_id UUID NOT NULL REFERENCES locations(id),
    name VARCHAR(255) NOT NULL,
    total_capacity INT NOT NULL
);