-- Ledger of every hold. Redis is the fast path, but Postgres decides whether a hold exists:
-- each step changes the ledger first, so a Redis call that ran without its DB step is replayed on redelivery.
CREATE TABLE stock_reservations (
    order_id   UUID        NOT NULL,
    store_id   UUID        NOT NULL,
    product_id UUID        NOT NULL,
    quantity   INTEGER     NOT NULL CHECK (quantity > 0),
    status     VARCHAR(16) NOT NULL DEFAULT 'HELD' CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (order_id, product_id)
);

CREATE INDEX idx_stock_reservations_held ON stock_reservations (store_id, product_id) WHERE status = 'HELD';
