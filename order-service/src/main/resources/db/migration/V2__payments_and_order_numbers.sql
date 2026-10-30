-- Gateway reference of the payment that paid the order; a refund needs it.
ALTER TABLE orders ADD COLUMN payment_reference VARCHAR(100);

-- Every payment result seen, keyed by the gateway's reference. A redelivered webhook is ignored, so each
-- captured payment is applied (or refunded) exactly once.
CREATE TABLE payment_attempts (
    gateway_reference VARCHAR(100)   PRIMARY KEY,
    order_id          UUID           NOT NULL,
    success           BOOLEAN        NOT NULL,
    amount            DECIMAL(10, 2),
    received_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_attempts_order ON payment_attempts (order_id);

-- Order numbers were "QC<date><6 random digits>": by the birthday bound, ~1,200 orders in a day give a 50%
-- chance that two collide on the UNIQUE column and that checkout fails. A sequence never repeats.
CREATE SEQUENCE order_number_seq;
