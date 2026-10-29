-- System of record for stock. Redis holds the live "sellable" counter:
--   redis = available_stock - reserved_stock
CREATE TABLE store_inventories (
    id                 BIGSERIAL PRIMARY KEY,
    store_id           UUID        NOT NULL,
    product_id         UUID        NOT NULL,
    available_stock    INTEGER     NOT NULL DEFAULT 0 CHECK (available_stock >= 0),
    reserved_stock     INTEGER     NOT NULL DEFAULT 0 CHECK (reserved_stock >= 0),
    shelf_bin_location VARCHAR(40),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_store_product UNIQUE (store_id, product_id)
);
