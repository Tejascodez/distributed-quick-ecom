-- Track the delivery-progress events for pickup and hand-over, so a send that failed is retried.
ALTER TABLE delivery_assignments ADD COLUMN picked_up_published_at TIMESTAMPTZ;
ALTER TABLE delivery_assignments ADD COLUMN delivered_published_at TIMESTAMPTZ;

CREATE INDEX idx_assignments_progress_unpublished ON delivery_assignments (order_id)
    WHERE cancelled_at IS NULL
      AND ((picked_up_at IS NOT NULL AND picked_up_published_at IS NULL)
        OR (delivered_at IS NOT NULL AND delivered_published_at IS NULL));
