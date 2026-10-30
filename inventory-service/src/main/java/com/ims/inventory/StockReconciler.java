package com.ims.inventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finds and (optionally) repairs drift between the three views of stock:
 * <ul>
 *   <li>Postgres: {@code reserved_stock} must equal the sum of HELD ledger rows.</li>
 *   <li>Redis: the sellable counter must equal {@code available_stock} minus every live hold. A hold is the order's
 *       Redis reservation hash, or its HELD ledger rows when Redis lost the hash (e.g. after a Redis restart).</li>
 * </ul>
 * Postgres is repaired exactly under a row lock. A Redis check can race a reserve or confirm in flight, so a Redis
 * counter is only corrected once two consecutive runs see the same delta, and by INCRBY so it never overwrites a
 * concurrent reservation. Repairs are off unless {@code ims.reconcile.repair} is true; drift is always logged.
 */
@Component
class StockReconciler {

    record Drift(UUID storeId, UUID productId, long delta, boolean repaired) {
    }

    record Report(List<Drift> postgres, List<Drift> redis) {
    }

    private record Row(UUID storeId, UUID productId, int available, int reserved, int held) {
    }

    private static final Logger log = LoggerFactory.getLogger(StockReconciler.class);
    private static final String LOCK = "inv:reconcile-lock";

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean repair;
    private final Duration interval;
    /** Redis deltas seen on the previous run, by stock key; a delta is only trusted when it repeats. */
    private Map<String, Long> previousRedisDeltas = new HashMap<>();

    StockReconciler(StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate tx,
                    @Value("${ims.reconcile.repair}") boolean repair,
                    @Value("${ims.reconcile.interval}") Duration interval) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.tx = tx;
        this.repair = repair;
        this.interval = interval;
    }

    @Scheduled(fixedDelayString = "${ims.reconcile.interval}", initialDelayString = "${ims.reconcile.interval}")
    void scheduled() {
        // One instance per interval: two instances applying the same INCRBY would double the correction.
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK, "1", interval.minusSeconds(5)))) {
            reconcile(repair);
        }
    }

    synchronized Report reconcile(boolean repair) {
        List<Row> rows = jdbc.query("""
                SELECT i.store_id, i.product_id, i.available_stock, i.reserved_stock, COALESCE(SUM(r.quantity), 0)
                FROM store_inventories i
                LEFT JOIN stock_reservations r
                       ON r.store_id = i.store_id AND r.product_id = i.product_id AND r.status = 'HELD'
                GROUP BY i.store_id, i.product_id, i.available_stock, i.reserved_stock
                """, (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getInt(3), rs.getInt(4), rs.getInt(5)));

        List<Drift> postgres = new ArrayList<>();
        for (Row row : rows) {
            if (row.reserved() != row.held()) {
                if (repair) {
                    repairReserved(row.storeId(), row.productId());
                }
                postgres.add(new Drift(row.storeId(), row.productId(), row.held() - row.reserved(), repair));
                log.warn("Postgres drift store={} product={} reserved_stock={} held={}{}", row.storeId(),
                        row.productId(), row.reserved(), row.held(), repair ? " (repaired)" : "");
            }
        }

        Map<UUID, List<Row>> byStore = new HashMap<>();
        rows.forEach(row -> byStore.computeIfAbsent(row.storeId(), k -> new ArrayList<>()).add(row));
        Map<String, Long> deltas = new HashMap<>();
        List<Drift> redisDrift = new ArrayList<>();
        byStore.forEach((storeId, storeRows) -> {
            Map<UUID, Long> heldByProduct = liveHolds(storeId);
            for (Row row : storeRows) {
                String key = StockKeys.stock(storeId, row.productId());
                String value = redis.opsForValue().get(key);
                long expected = row.available() - heldByProduct.getOrDefault(row.productId(), 0L);
                long delta = expected - (value == null ? 0 : Long.parseLong(value));
                if (delta == 0) {
                    continue;
                }
                deltas.put(key, delta);
                boolean confirmed = previousRedisDeltas.getOrDefault(key, 0L) == delta;
                boolean fix = repair && confirmed;
                if (fix) {
                    redis.opsForValue().increment(key, delta);
                    deltas.remove(key);
                }
                redisDrift.add(new Drift(storeId, row.productId(), delta, fix));
                log.warn("Redis drift store={} product={} counter off by {}{}", storeId, row.productId(), delta,
                        fix ? " (repaired)" : confirmed ? "" : " (unconfirmed, rechecking next run)");
            }
        });
        previousRedisDeltas = deltas;
        return new Report(postgres, redisDrift);
    }

    /** Units held per product in one store: each order's Redis hash, or its HELD ledger rows if the hash is gone. */
    private Map<UUID, Long> liveHolds(UUID storeId) {
        Map<UUID, Map<UUID, Long>> byOrder = new HashMap<>();
        jdbc.query("SELECT order_id, product_id, quantity FROM stock_reservations WHERE store_id = ? AND status = 'HELD'",
                rs -> {
                    byOrder.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>())
                            .put(rs.getObject(2, UUID.class), (long) rs.getInt(3));
                }, storeId);
        String pattern = StockKeys.reservationPrefix(storeId) + "*";
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
            while (cursor.hasNext()) {
                String reservation = cursor.next();
                Map<UUID, Long> lines = new HashMap<>();
                redis.<String, String>opsForHash().entries(reservation).forEach((stockKey, qty) ->
                        lines.put(StockKeys.trailingId(stockKey), Long.parseLong(qty)));
                // The hash is what Redis actually took off the counters, so it wins over the ledger.
                byOrder.put(StockKeys.trailingId(reservation), lines);
            }
        }
        Map<UUID, Long> held = new HashMap<>();
        byOrder.values().forEach(lines -> lines.forEach((product, qty) -> held.merge(product, qty, Long::sum)));
        return held;
    }

    /**
     * Locks the row first: a reservation that already adjusted it has then committed and its ledger rows are visible
     * to the next statement; one that hasn't adjusted yet adds its own quantity after us.
     */
    private void repairReserved(UUID storeId, UUID productId) {
        tx.executeWithoutResult(s -> {
            jdbc.query("SELECT 1 FROM store_inventories WHERE store_id = ? AND product_id = ? FOR UPDATE",
                    rs -> { }, storeId, productId);
            jdbc.update("""
                    UPDATE store_inventories
                    SET reserved_stock = (SELECT COALESCE(SUM(quantity), 0) FROM stock_reservations
                                          WHERE store_id = ? AND product_id = ? AND status = 'HELD'),
                        updated_at = now()
                    WHERE store_id = ? AND product_id = ?
                    """, storeId, productId, storeId, productId);
        });
    }
}
