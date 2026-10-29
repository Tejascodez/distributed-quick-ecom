package com.ims.common.events;

import java.util.UUID;

/** Published on inventory-reserved / inventory-rejected. */
public record InventoryResultEvent(UUID orderId, UUID storeId, boolean reserved, String reason) {
}
