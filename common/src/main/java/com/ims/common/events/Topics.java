package com.ims.common.events;

/** Kafka topic names shared across services. */
public final class Topics {
    public static final String ORDER_CREATED = "order-created";
    public static final String INVENTORY_RESERVED = "inventory-reserved";
    public static final String INVENTORY_REJECTED = "inventory-rejected";
    public static final String PAYMENT_SUCCESS = "payment-success";
    public static final String PAYMENT_FAILED = "payment-failed";
    public static final String PAYMENT_REFUND_REQUESTED = "payment-refund-requested";
    public static final String ORDER_PAID = "order-paid-event";
    public static final String ORDER_CANCELLED = "order-cancelled";
    public static final String RIDER_ASSIGNED = "rider-assigned";
    public static final String ORDER_STATUS_CHANGED = "order-status-changed";

    private Topics() {
    }
}
