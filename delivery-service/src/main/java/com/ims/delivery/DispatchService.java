package com.ims.delivery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.ims.common.events.RiderAssignedEvent;
import com.ims.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
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

    enum Outcome { DONE, NO_RIDER }

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
    /** Upper bound per drain; orders are grouped by store, so one starved store can't hold up the others. */
    private static final int MAX_PER_DRAIN = 1_000;
    private static final double AVG_SPEED_KMPH = 20;
    private static final int HANDOVER_MINUTES = 3;

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StoreClient stores;
    private final RiderPool pool;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final double radiusKm;

    DispatchService(StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate tx, StoreClient stores,
                    RiderPool pool, KafkaTemplate<String, String> kafka, JsonMapper json,
                    @Value("${ims.dispatch-radius-km}") double radiusKm) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.tx = tx;
        this.stores = stores;
        this.pool = pool;
        this.kafka = kafka;
        this.json = json;
        this.radiusKm = radiusKm;
    }

    void enqueue(UUID orderId, UUID storeId) {
        redis.opsForZSet().addIfAbsent(RiderKeys.PENDING, member(orderId, storeId), Instant.now().toEpochMilli());
    }

    @Scheduled(fixedDelayString = "PT5S")
    void drain() {
        var batch = redis.opsForZSet().range(RiderKeys.PENDING, 0, MAX_PER_DRAIN - 1);
        if (batch != null) {
            // Oldest first; once a store has no free rider this round, its later orders wait for the next one.
            Set<UUID> noRiders = new HashSet<>();
            for (String member : batch) {
                String[] parts = member.split("\\|");
                UUID orderId = UUID.fromString(parts[0]);
                UUID storeId = UUID.fromString(parts[1]);
                if (noRiders.contains(storeId)) {
                    continue;
                }
                try {
                    if (tryAssign(orderId, storeId) == Outcome.DONE) {
                        redis.opsForZSet().remove(RiderKeys.PENDING, member);
                    } else {
                        noRiders.add(storeId);
                    }
                } catch (RuntimeException e) {
                    log.warn("Dispatch of order {} failed; retrying next round", orderId, e);
                }
            }
        }
        republishUnsent();
    }

    /** DONE when the order no longer needs dispatching: assigned now, earlier, or cancelled. */
    Outcome tryAssign(UUID orderId, UUID storeId) {
        if (alreadyHandled(orderId)) {
            return Outcome.DONE; // order-paid redelivered, or the order was cancelled
        }
        var store = stores.location(storeId);
        var candidates = redis.opsForGeo().search(RiderKeys.IDLE,
                GeoReference.fromCoordinate(new Point(store.lng(), store.lat())),
                new Distance(radiusKm, Metrics.KILOMETERS),
                GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(5));
        if (candidates == null) {
            return Outcome.NO_RIDER;
        }
        for (var candidate : candidates) {
            UUID partnerId = UUID.fromString(candidate.getContent().getName());
            if (!pool.claim(partnerId, orderId)) {
                continue;
            }
            double km = candidate.getDistance().getValue();
            int eta = (int) Math.ceil(km / AVG_SPEED_KMPH * 60) + HANDOVER_MINUTES;
            boolean inserted;
            try {
                inserted = Boolean.TRUE.equals(tx.execute(s -> insertAssignment(orderId, partnerId, storeId, km, eta)));
            } catch (DuplicateKeyException e) {
                // Postgres says this rider already has an open delivery that Redis lost track of.
                pool.release(partnerId, orderId);
                pool.restoreMarker(partnerId);
                continue;
            } catch (RuntimeException e) {
                pool.release(partnerId, orderId);
                throw e;
            }
            if (!inserted) {
                pool.release(partnerId, orderId); // another dispatcher assigned it, or it was cancelled meanwhile
                return Outcome.DONE;
            }
            pool.confirm(partnerId);
            log.info("Assigned rider {} to order {} ({} km, eta {} min)", partnerId, orderId, km, eta);
            publishAssigned(orderId, partnerId, storeId, eta);
            return Outcome.DONE;
        }
        return Outcome.NO_RIDER;
    }

    private boolean insertAssignment(UUID orderId, UUID partnerId, UUID storeId, double km, int eta) {
        lockOrder(orderId);
        int rows = jdbc.update("""
                INSERT INTO delivery_assignments (id, order_id, partner_id, store_id, route_distance_km, estimated_minutes)
                SELECT ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM cancelled_orders WHERE order_id = ?)
                ON CONFLICT (order_id) DO NOTHING
                """, UUID.randomUUID(), orderId, partnerId, storeId,
                BigDecimal.valueOf(km).setScale(2, RoundingMode.HALF_UP), eta, orderId);
        if (rows == 1) {
            jdbc.update("UPDATE delivery_partners SET current_status = 'ASSIGNED' WHERE id = ?", partnerId);
        }
        return rows == 1;
    }

    /**
     * Order cancelled upstream. Stops dispatching it and ends its delivery, freeing the rider. The cancel wins even
     * after pickup: order-service has already refunded the customer, so a rider who picked it up returns it to the
     * store (delivered then answers 409). Runs under the same per-order lock as the assignment insert, so a dispatch
     * in flight either sees the cancellation or has already committed the assignment this then cancels.
     */
    void cancel(UUID orderId, UUID storeId) {
        redis.opsForZSet().remove(RiderKeys.PENDING, member(orderId, storeId));
        record Freed(UUID partnerId, boolean pickedUp) {
        }
        List<Freed> freed = tx.execute(s -> {
            lockOrder(orderId);
            jdbc.update("INSERT INTO cancelled_orders (order_id) VALUES (?) ON CONFLICT DO NOTHING", orderId);
            List<Freed> partner = jdbc.query("""
                    UPDATE delivery_assignments SET cancelled_at = now()
                    WHERE order_id = ? AND delivered_at IS NULL AND cancelled_at IS NULL
                    RETURNING partner_id, picked_up_at IS NOT NULL
                    """, (rs, i) -> new Freed(rs.getObject(1, UUID.class), rs.getBoolean(2)), orderId);
            partner.forEach(f -> jdbc.update("UPDATE delivery_partners SET current_status = 'IDLE' WHERE id = ?",
                    f.partnerId()));
            return partner;
        });
        if (!freed.isEmpty()) {
            Freed f = freed.getFirst();
            pool.release(f.partnerId(), orderId);
            if (f.pickedUp()) {
                log.warn("Order {} cancelled after pickup; rider {} must return it to the store", orderId, f.partnerId());
            } else {
                log.info("Order {} cancelled; rider {} freed", orderId, f.partnerId());
            }
        }
    }

    /** Waits for the broker; an unacknowledged event stays unpublished and is retried by the next drain. */
    private void publishAssigned(UUID orderId, UUID partnerId, UUID storeId, int eta) {
        try {
            kafka.send(Topics.RIDER_ASSIGNED, orderId.toString(),
                    json.writeValueAsString(new RiderAssignedEvent(orderId, partnerId, storeId, eta))).join();
            jdbc.update("UPDATE delivery_assignments SET event_published_at = now() WHERE order_id = ?", orderId);
        } catch (RuntimeException e) {
            log.warn("Could not publish rider-assigned for order {}; will retry", orderId, e);
        }
    }

    void republishUnsent() {
        jdbc.query("""
                SELECT order_id, partner_id, store_id, estimated_minutes FROM delivery_assignments
                WHERE event_published_at IS NULL AND cancelled_at IS NULL AND assigned_at < now() - interval '10 seconds'
                ORDER BY assigned_at LIMIT 100
                """, (rs, i) -> new RiderAssignedEvent(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getInt(4)))
                .forEach(e -> publishAssigned(e.orderId(), e.partnerId(), e.storeId(), e.estimatedMinutes()));
    }

    private boolean alreadyHandled(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM delivery_assignments WHERE order_id = ?)
                    OR EXISTS (SELECT 1 FROM cancelled_orders WHERE order_id = ?)
                """, Boolean.class, orderId, orderId));
    }

    private void lockOrder(UUID orderId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, orderId.toString());
    }

    private static String member(UUID orderId, UUID storeId) {
        return orderId + "|" + storeId;
    }
}
