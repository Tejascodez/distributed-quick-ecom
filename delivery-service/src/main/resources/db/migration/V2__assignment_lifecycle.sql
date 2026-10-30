-- An assignment ends delivered or cancelled; event_published_at tracks the rider-assigned event so a send
-- that failed is retried.
ALTER TABLE delivery_assignments ADD COLUMN cancelled_at TIMESTAMPTZ;
ALTER TABLE delivery_assignments ADD COLUMN event_published_at TIMESTAMPTZ;

-- Backstop behind the Redis claim: a rider can hold at most one open delivery.
CREATE UNIQUE INDEX uq_assignments_open_per_partner ON delivery_assignments (partner_id)
    WHERE delivered_at IS NULL AND cancelled_at IS NULL;

CREATE INDEX idx_assignments_unpublished ON delivery_assignments (assigned_at)
    WHERE event_published_at IS NULL AND cancelled_at IS NULL;

-- Orders cancelled upstream. order-cancelled and order-paid travel on different topics, so a cancel can
-- arrive before (or while) the order is dispatched; dispatch checks this under the same per-order lock.
CREATE TABLE cancelled_orders (
    order_id     UUID        PRIMARY KEY,
    cancelled_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
