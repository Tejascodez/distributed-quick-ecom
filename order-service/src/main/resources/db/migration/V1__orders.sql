CREATE TABLE orders (
    id                  UUID PRIMARY KEY,
    order_number        VARCHAR(32)    NOT NULL UNIQUE,
    user_id             UUID           NOT NULL,
    store_id            UUID           NOT NULL,
    delivery_address_id UUID           NOT NULL,
    total_item_amount   DECIMAL(10, 2) NOT NULL,
    delivery_fee        DECIMAL(10, 2) NOT NULL DEFAULT 0,
    surge_fee           DECIMAL(10, 2) NOT NULL DEFAULT 0,
    payable_amount      DECIMAL(10, 2) NOT NULL,
    order_status        VARCHAR(24)    NOT NULL,
    payment_status      VARCHAR(16)    NOT NULL DEFAULT 'PENDING'
        CHECK (payment_status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED')),
    idempotency_key     VARCHAR(100)   NOT NULL UNIQUE, -- "<userId>:<client key>"
    version             BIGINT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_user ON orders (user_id);
CREATE INDEX idx_orders_store ON orders (store_id);
CREATE INDEX idx_orders_status_created ON orders (order_status, created_at);

CREATE TABLE order_items (
    id           BIGSERIAL PRIMARY KEY,
    order_id     UUID           NOT NULL REFERENCES orders (id),
    product_id   UUID           NOT NULL,
    product_name VARCHAR(200)   NOT NULL, -- snapshot for audit history
    unit_price   DECIMAL(10, 2) NOT NULL, -- snapshot
    quantity     INTEGER        NOT NULL CHECK (quantity > 0),
    total_price  DECIMAL(10, 2) NOT NULL
);
CREATE INDEX idx_order_items_order ON order_items (order_id);

-- Transactional outbox, tailed by Debezium (infra/debezium/order-outbox-connector.json).
-- event_type is the destination Kafka topic.
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(40)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_created ON outbox_events (created_at);
