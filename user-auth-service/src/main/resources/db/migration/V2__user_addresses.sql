CREATE TABLE user_addresses (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    label      VARCHAR(30)  NOT NULL,             -- Home, Work, ...
    line1      VARCHAR(200) NOT NULL,
    line2      VARCHAR(200),
    city       VARCHAR(80)  NOT NULL,
    pincode    VARCHAR(10)  NOT NULL,
    lat        DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng        DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN -180 AND 180),
    is_default BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_addresses_user ON user_addresses (user_id);
-- At most one default address per user.
CREATE UNIQUE INDEX uq_user_default_address ON user_addresses (user_id) WHERE is_default;
