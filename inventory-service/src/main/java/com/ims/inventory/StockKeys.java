package com.ims.inventory;

import java.util.UUID;

/** Redis key layout. The {storeId} hash tag pins one store's keys to one cluster slot. */
final class StockKeys {

    static final String EXPIRY_INDEX = "inv:reservation-expiry";

    static String stock(UUID storeId, UUID productId) {
        return "inv:{" + storeId + "}:" + productId;
    }

    static String reservation(UUID storeId, UUID orderId) {
        return reservationPrefix(storeId) + orderId;
    }

    /** Prefix shared by every reservation hash of one store; the order id follows it. */
    static String reservationPrefix(UUID storeId) {
        return "res:{" + storeId + "}:";
    }

    /** The trailing id of a stock or reservation key. */
    static UUID trailingId(String key) {
        return UUID.fromString(key.substring(key.lastIndexOf(':') + 1));
    }

    private StockKeys() {
    }
}
