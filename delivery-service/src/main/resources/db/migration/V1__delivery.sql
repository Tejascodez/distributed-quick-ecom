CREATE TABLE delivery_partners (
    id             UUID PRIMARY KEY,
    full_name      VARCHAR(120) NOT NULL,
    phone          VARCHAR(20)  NOT NULL UNIQUE,
    vehicle_type   VARCHAR(16)  NOT NULL CHECK (vehicle_type IN ('BIKE', 'EV_SCOOTER', 'CYCLE')),
    is_online      BOOLEAN      NOT NULL DEFAULT FALSE,
    current_status VARCHAR(16)  NOT NULL DEFAULT 'IDLE'
        CHECK (current_status IN ('IDLE', 'ASSIGNED', 'DELIVERING')),
    rating         DECIMAL(2, 1) NOT NULL DEFAULT 5.0
);

CREATE TABLE delivery_assignments (
    id                UUID PRIMARY KEY,
    order_id          UUID         NOT NULL UNIQUE,
    partner_id        UUID         NOT NULL REFERENCES delivery_partners (id),
    store_id          UUID         NOT NULL,
    assigned_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    picked_up_at      TIMESTAMPTZ,
    delivered_at      TIMESTAMPTZ,
    route_distance_km DECIMAL(5, 2),
    estimated_minutes INTEGER
);
CREATE INDEX idx_assignments_partner ON delivery_assignments (partner_id);
