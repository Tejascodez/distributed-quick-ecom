package com.ims.common.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(UUID orderId, UUID userId, UUID storeId, List<OrderLine> items, BigDecimal payableAmount) {
}
