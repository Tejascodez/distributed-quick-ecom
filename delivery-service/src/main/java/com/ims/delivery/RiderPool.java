package com.ims.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The idle-rider pool in Redis. Every change to who is idle or busy goes through an atomic script, and
 * Postgres (open assignments) is the source of truth that busy markers are rebuilt from.
 */
@Component
class RiderPool {

    private static final Logger log = LoggerFactory.getLogger(RiderPool.class);
    private static final RedisScript<Long> CLAIM = RedisScript.of(new ClassPathResource("scripts/claim.lua"), Long.class);
    private static final RedisScript<String> PING = RedisScript.of(new ClassPathResource("scripts/ping.lua"), String.class);
    private static final RedisScript<Long> RELEASE = RedisScript.of(new ClassPathResource("scripts/release.lua"), Long.class);
    /** How long a claim may take to commit before its rider is freed again. */
    private static final Duration CLAIM_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final Duration staleAfter;

    RiderPool(StringRedisTemplate redis, JdbcTemplate jdbc, @Value("${ims.rider-stale-after}") Duration staleAfter) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.staleAfter = staleAfter;
    }

    /** Records a location ping. Returns the order the rider is delivering, or null if they are free. */
    UUID ping(UUID riderId, double lat, double lng) {
        String active = redis.execute(PING,
                List.of(RiderKeys.IDLE, RiderKeys.ONLINE, RiderKeys.activeOrder(riderId), RiderKeys.LAST_SEEN),
                riderId.toString(), String.valueOf(lng), String.valueOf(lat),
                String.valueOf(Instant.now().toEpochMilli()));
        return active == null ? null : UUID.fromString(active);
    }

    void online(UUID riderId) {
        redis.opsForSet().add(RiderKeys.ONLINE, riderId.toString());
    }

    /** Leaves ONLINE first: a ping racing this call then can't re-add the rider after the ZREM. */
    void offline(UUID riderId) {
        redis.opsForSet().remove(RiderKeys.ONLINE, riderId.toString());
        redis.opsForZSet().remove(RiderKeys.IDLE, riderId.toString());
    }

    /** Takes an idle rider out of the pool for {@code orderId}; false if someone else got them first. */
    boolean claim(UUID riderId, UUID orderId) {
        Long claimed = redis.execute(CLAIM, List.of(RiderKeys.IDLE, RiderKeys.activeOrder(riderId)),
                riderId.toString(), orderId.toString(), String.valueOf(CLAIM_TTL.toSeconds()));
        return claimed != null && claimed == 1;
    }

    /** The assignment committed: the busy marker now lives until delivery or cancellation. */
    void confirm(UUID riderId) {
        redis.persist(RiderKeys.activeOrder(riderId));
    }

    /** Frees the rider from {@code orderId} (no-op if they've moved on); they rejoin on their next ping. */
    void release(UUID riderId, UUID orderId) {
        redis.execute(RELEASE, List.of(RiderKeys.activeOrder(riderId)), orderId.toString());
    }

    @EventListener(ApplicationReadyEvent.class)
    void warm() {
        jdbc.query("SELECT id FROM delivery_partners WHERE is_online", rs -> {
            online(rs.getObject(1, UUID.class));
        });
        restoreMarkers();
    }

    @Scheduled(fixedDelayString = "PT15S")
    void maintain() {
        dropSilentRiders();
        restoreMarkers();
    }

    /** A rider whose app stopped pinging (killed, no signal) must not be handed orders. */
    void dropSilentRiders() {
        long cutoff = Instant.now().minus(staleAfter).toEpochMilli();
        var silent = redis.opsForZSet().rangeByScore(RiderKeys.LAST_SEEN, 0, cutoff);
        if (silent == null || silent.isEmpty()) {
            return;
        }
        redis.opsForZSet().remove(RiderKeys.IDLE, silent.toArray());
        redis.opsForZSet().removeRangeByScore(RiderKeys.LAST_SEEN, 0, cutoff);
        log.info("Dropped {} riders silent for over {}", silent.size(), staleAfter);
    }

    /**
     * Makes Redis agree with Postgres after a Redis restart or a crash mid-delivery: every rider with an open
     * assignment gets their busy marker back, and a permanent marker with no open assignment behind it is cleared.
     * Markers that still have a TTL belong to claims in flight and are left alone.
     */
    void restoreMarkers() {
        Map<UUID, UUID> open = openAssignments(null);
        open.forEach((riderId, orderId) -> {
            redis.opsForValue().setIfAbsent(RiderKeys.activeOrder(riderId), orderId.toString());
            redis.opsForZSet().remove(RiderKeys.IDLE, riderId.toString());
        });
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(RiderKeys.ACTIVE_PREFIX + "*").count(500).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                UUID riderId = UUID.fromString(key.substring(RiderKeys.ACTIVE_PREFIX.length()));
                Long ttl = redis.getExpire(key);
                if (open.containsKey(riderId) || ttl == null || ttl != -1) {
                    continue;
                }
                // Re-check this one rider: an assignment may have committed since the bulk read above.
                if (openAssignments(riderId).isEmpty()) {
                    String orderId = redis.opsForValue().get(key);
                    if (orderId != null) {
                        release(riderId, UUID.fromString(orderId));
                        log.info("Cleared busy marker of rider {} for closed order {}", riderId, orderId);
                    }
                }
            }
        }
    }

    /** Puts back the marker for one rider's open assignment, when Postgres knows about it but Redis didn't. */
    void restoreMarker(UUID riderId) {
        openAssignments(riderId).forEach((rider, orderId) ->
                redis.opsForValue().set(RiderKeys.activeOrder(rider), orderId.toString()));
    }

    /** Open (undelivered, uncancelled) assignments by rider; all riders when {@code riderId} is null. */
    private Map<UUID, UUID> openAssignments(UUID riderId) {
        Map<UUID, UUID> open = new HashMap<>();
        jdbc.query("""
                SELECT partner_id, order_id FROM delivery_assignments
                WHERE delivered_at IS NULL AND cancelled_at IS NULL AND (?::uuid IS NULL OR partner_id = ?::uuid)
                """, rs -> {
            open.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
        }, riderId, riderId);
        return open;
    }
}
