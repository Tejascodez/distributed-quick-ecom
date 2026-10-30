package com.ims.order;

import java.util.Set;

/**
 * Order lifecycle state machine. Store ops move a paid order through PICKING and PACKED; delivery-service moves it
 * to OUT_FOR_DELIVERY at pickup, even if ops never marked it packed (the rider has it in hand), and to DELIVERED.
 */
public enum OrderStatus {
    CREATED, INVENTORY_RESERVED, PAID, PICKING, PACKED, OUT_FOR_DELIVERY, DELIVERED, CANCELLED;

    public boolean canTransitionTo(OrderStatus next) {
        return allowedNext().contains(next);
    }

    private Set<OrderStatus> allowedNext() {
        return switch (this) {
            case CREATED -> Set.of(INVENTORY_RESERVED, CANCELLED);
            case INVENTORY_RESERVED -> Set.of(PAID, CANCELLED);
            case PAID -> Set.of(PICKING, OUT_FOR_DELIVERY, CANCELLED);
            case PICKING -> Set.of(PACKED, OUT_FOR_DELIVERY, CANCELLED);
            case PACKED -> Set.of(OUT_FOR_DELIVERY);
            case OUT_FOR_DELIVERY -> Set.of(DELIVERED);
            case DELIVERED, CANCELLED -> Set.of();
        };
    }
}
