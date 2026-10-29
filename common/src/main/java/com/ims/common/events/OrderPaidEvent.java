package com.ims.common.events;

import java.util.UUID;

public record OrderPaidEvent(UUID orderId, UUID userId, UUID storeId) {
}
