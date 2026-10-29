CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE dark_stores (
    id                        UUID PRIMARY KEY,
    name                      VARCHAR(120) NOT NULL,
    address                   TEXT         NOT NULL,
    location                  GEOMETRY(Point, 4326) NOT NULL,
    h3_cell                   BIGINT       NOT NULL,
    serviceable_radius_meters INTEGER      NOT NULL DEFAULT 3000,
    status                    VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'OVERLOADED')),
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Geography index so ST_DWithin works in metres and still hits the index.
CREATE INDEX idx_dark_stores_location_geog ON dark_stores USING GIST ((location::geography));
CREATE INDEX idx_dark_stores_h3 ON dark_stores (h3_cell);

CREATE TABLE categories (
    id        UUID PRIMARY KEY,
    name      VARCHAR(80) NOT NULL UNIQUE,
    parent_id UUID REFERENCES categories (id)
);

CREATE TABLE products (
    id          UUID PRIMARY KEY,
    sku         VARCHAR(64)    NOT NULL UNIQUE,
    name        VARCHAR(200)   NOT NULL,
    brand       VARCHAR(80),
    category_id UUID           REFERENCES categories (id),
    base_price  DECIMAL(10, 2) NOT NULL,
    unit        VARCHAR(10)    NOT NULL CHECK (unit IN ('g', 'ml', 'piece', 'kg'))
);
