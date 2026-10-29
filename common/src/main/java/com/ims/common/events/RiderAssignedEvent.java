package com.ims.common.events;

import java.util.UUID;

public record RiderAssignedEvent(UUID orderId, UUID partnerId, UUID storeId, int estimatedMinutes) {
}
