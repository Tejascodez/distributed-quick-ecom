package com.ims.inventory;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Returns stock held by checkouts that never paid within the reservation window. */
@Component
class ReservationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    private final StringRedisTemplate redis;
    private final ReservationService reservations;

    ReservationExpirySweeper(StringRedisTemplate redis, ReservationService reservations) {
        this.redis = redis;
        this.reservations = reservations;
    }

    @Scheduled(fixedDelayString = "PT30S")
    void sweep() {
        var expired = redis.opsForZSet().rangeByScore(StockKeys.EXPIRY_INDEX, 0, Instant.now().toEpochMilli());
        if (expired == null) {
            return;
        }
        for (String member : expired) {
            String[] parts = member.split("\\|");
            UUID storeId = UUID.fromString(parts[0]);
            UUID orderId = UUID.fromString(parts[1]);
            reservations.release(orderId, storeId);
            log.info("Released expired reservation order={} store={}", orderId, storeId);
        }
    }
}
