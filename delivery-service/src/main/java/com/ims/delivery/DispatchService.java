package com.ims.delivery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import com.ims.common.events.RiderAssignedEvent;
import com.ims.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Matches paid orders to the nearest idle rider around the dark store.
 * Orders are queued and drained in batches so a burst of orders is matched together
 * (greedy nearest-first today; swap in a Hungarian / min-cost matcher per batch later).
 */
@Service
class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
    private static final int BATCH_SIZE = 50;
    private static final double AVG_SPEED_KMPH = 20;
    private static final int HANDOVER_MINUTES = 3;

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StoreClient stores;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final double radiusKm;

    DispatchService(StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate tx, StoreClient stores,
                    KafkaTemplate<String, String> kafka, JsonMapper json,
                    @Value("${ims.dispatch-radius-km}") double radiusKm) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.tx = tx;
        this.stores = stores;
        this.kafka = kafka;
        this.json = json;
        this.radiusKm = radiusKm;
    }

    void enqueue(UUID orderId, UUID storeId) {
        redis.opsForZSet().addIfAbsent(RiderKeys.PENDING, orderId + "|" + storeId, Instant.now().toEpochMilli());
    }

    @Scheduled(fixedDelayString = "PT5S")
    void drain() {
        var batch = redis.opsForZSet().range(RiderKeys.PENDING, 0, BATCH_SIZE - 1);
        if (batch == null) {
            return;
        }
        for (String member : batch) {
            String[] parts = member.split("\\|");
            if (tryAssign(UUID.fromString(parts[0]), UUID.fromString(parts[1]))) {
                redis.opsForZSet().remove(RiderKeys.PENDING, member);
            }
        }
    }

    /** Returns true when the order no longer needs dispatching (assigned now or earlier). */
    private boolean tryAssign(UUID orderId, UUID storeId) {
        // order-paid can be redelivered (at-least-once); don't burn a second rider on it.
        if (alreadyAssigned(orderId)) {
            return true;
        }
        var store = stores.location(storeId);
        var candidates = redis.opsForGeo().search(RiderKeys.IDLE,
                GeoReference.fromCoordinate(new Point(store.lng(), store.lat())),
                new Distance(radiusKm, Metrics.KILOMETERS),
                GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().includeCoordinates().sortAscending().limit(5));
        if (candidates == null) {
            return false;
        }
        for (var candidate : candidates) {
            String riderId = candidate.getContent().getName();
            // ZREM is the claim: only one dispatcher instance can win a given rider.
            Long removed = redis.opsForZSet().remove(RiderKeys.IDLE, riderId);
            if (removed == null || removed == 0) {
                continue;
            }
            double km = candidate.getDistance().getValue();
            int eta = (int) Math.ceil(km / AVG_SPEED_KMPH * 60) + HANDOVER_MINUTES;
            UUID partnerId = UUID.fromString(riderId);
            Boolean inserted = tx.execute(s -> {
                int rows = jdbc.update("""
                        INSERT INTO delivery_assignments (id, order_id, partner_id, store_id, route_distance_km, estimated_minutes)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (order_id) DO NOTHING
                        """, UUID.randomUUID(), orderId, partnerId, storeId,
                        BigDecimal.valueOf(km).setScale(2, RoundingMode.HALF_UP), eta);
                if (rows == 1) {
                    jdbc.update("UPDATE delivery_partners SET current_status = 'ASSIGNED' WHERE id = ?", partnerId);
                }
                return rows == 1;
            });
            if (!Boolean.TRUE.equals(inserted)) {
                // Another dispatcher assigned this order concurrently: put our rider back in the pool.
                redis.opsForGeo().add(RiderKeys.IDLE, candidate.getContent().getPoint(), riderId);
                return true;
            }
            redis.opsForValue().set(RiderKeys.activeOrder(partnerId), orderId.toString());
            kafka.send(Topics.RIDER_ASSIGNED, orderId.toString(),
                    json.writeValueAsString(new RiderAssignedEvent(orderId, partnerId, storeId, eta)));
            log.info("Assigned rider {} to order {} ({} km, eta {} min)", partnerId, orderId, km, eta);
            return true;
        }
        return false;
    }

    private boolean alreadyAssigned(UUID orderId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM delivery_assignments WHERE order_id = ?)", Boolean.class, orderId);
        return Boolean.TRUE.equals(exists);
    }
}
