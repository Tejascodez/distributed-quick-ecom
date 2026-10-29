package com.ims.common.events;

import java.util.List;
import java.util.UUID;

/** Compensation trigger: inventory releases reservations for these lines. */
public record OrderCancelledEvent(UUID orderId, UUID storeId, List<OrderLine> items, String reason) {
}
