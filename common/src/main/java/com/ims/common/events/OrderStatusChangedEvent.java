package com.ims.common.events;

import java.util.UUID;

public record OrderStatusChangedEvent(UUID orderId, UUID userId, String status) {
}
