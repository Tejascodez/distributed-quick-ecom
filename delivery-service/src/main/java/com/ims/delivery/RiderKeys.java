package com.ims.delivery;

import java.util.UUID;

/**
 * Redis key layout. Rider keys share the {riders} hash tag so the claim and ping scripts, which touch
 * several of them atomically, run on one Redis Cluster slot.
 */
final class RiderKeys {

    /** GEO set of online riders free to take a task. */
    static final String IDLE = "{riders}:idle";
    /** Riders who are online (tapped "online" and not "offline"); only they may enter the idle pool. */
    static final String ONLINE = "{riders}:online";
    /** Last location ping per rider, score = epoch millis; silent riders are dropped from the pool. */
    static final String LAST_SEEN = "{riders}:last-seen";
    /** Orders waiting for a rider: member "orderId|storeId", score = enqueue time. */
    static final String PENDING = "dispatch:pending";
    static final String ACTIVE_PREFIX = "{riders}:active:";

    /** The order a rider is busy with. Set by the claim, so a busy rider can never re-enter the pool. */
    static String activeOrder(UUID partnerId) {
        return ACTIVE_PREFIX + partnerId;
    }

    static String locationChannel(UUID orderId) {
        return "rider-location:" + orderId;
    }

    private RiderKeys() {
    }
}
