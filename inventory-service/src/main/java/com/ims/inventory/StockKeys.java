package com.ims.inventory;

import java.util.UUID;

/** Redis key layout. The {storeId} hash tag pins one store's keys to one cluster slot. */
final class StockKeys {

    static final String EXPIRY_INDEX = "inv:reservation-expiry";

    static String stock(UUID storeId, UUID productId) {
        return "inv:{" + storeId + "}:" + productId;
    }

    static String reservation(UUID storeId, UUID orderId) {
        return "res:{" + storeId + "}:" + orderId;
    }

    static UUID productId(String stockKey) {
        return UUID.fromString(stockKey.substring(stockKey.lastIndexOf(':') + 1));
    }

    private StockKeys() {
    }
}
