package com.ims.notification;

import java.time.Duration;
import java.util.UUID;

import com.ims.common.security.Caller;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Who placed each order, so only they can watch it. Learned from the order events this service already consumes;
 * a customer who subscribes before those arrive (the outbox lags checkout by a moment) is checked with
 * order-service instead. An order's owner never changes, so a cached answer is final.
 */
@Component
class OrderOwners {

    /** Long enough to cover an order's whole life; after that, a late subscriber just costs one lookup. */
    private static final Duration TTL = Duration.ofDays(1);

    private final StringRedisTemplate redis;
    private final OrderClient orders;

    OrderOwners(StringRedisTemplate redis, OrderClient orders) {
        this.redis = redis;
        this.orders = orders;
    }

    void remember(UUID orderId, UUID userId) {
        redis.opsForValue().set(key(orderId), userId.toString(), TTL);
    }

    boolean isOwner(UUID orderId, Caller caller) {
        String owner = redis.opsForValue().get(key(orderId));
        if (owner != null) {
            return owner.equals(caller.userId().toString());
        }
        if (orders.canRead(orderId, caller)) { // only the owner gets a 200 among non-staff callers
            remember(orderId, caller.userId());
            return true;
        }
        return false;
    }

    static String key(UUID orderId) {
        return "notify:order-owner:" + orderId;
    }
}
