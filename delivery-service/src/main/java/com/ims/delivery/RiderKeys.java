package com.ims.delivery;

import java.util.UUID;

final class RiderKeys {

    /** GEO set of online riders free to take a task. */
    static final String IDLE = "riders:idle";
    /** Orders waiting for a rider: member "orderId|storeId", score = enqueue time. */
    static final String PENDING = "dispatch:pending";

    static String activeOrder(UUID partnerId) {
        return "rider:" + partnerId + ":order";
    }

    static String locationChannel(UUID orderId) {
        return "rider-location:" + orderId;
    }

    private RiderKeys() {
    }
}
