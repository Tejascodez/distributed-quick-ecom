package com.ims.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ims.common.events.OrderLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two-phase stock deduction: Redis/Lua is the hot-path reservation barrier, Postgres is updated behind it.
 * The stock_reservations ledger is the source of truth for which holds exist. Every step is idempotent and
 * ordered so that a failure between Redis and Postgres is repaired by the Kafka redelivery, not left as drift.
 */
@Service
public class ReservationService {

    public enum Result { RESERVED, ALREADY_RESERVED, OUT_OF_STOCK }

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RELEASE = RedisScript.of(new ClassPathResource("scripts/release.lua"), List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CONFIRM = RedisScript.of(new ClassPathResource("scripts/confirm.lua"), List.class);
    private static final RedisScript<Long> RESERVE = RedisScript.of(new ClassPathResource("scripts/reserve.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration ttl;

    ReservationService(StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate tx,
                       @Value("${ims.reservation-ttl}") Duration ttl) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.tx = tx;
        this.ttl = ttl;
    }

    public Result reserve(UUID orderId, UUID storeId, List<OrderLine> requested) {
        List<OrderLine> lines = merge(requested);
        String status = holdStatus(orderId);
        if (status != null && !status.equals("HELD")) {
            return Result.ALREADY_RESERVED; // late redelivery of a settled order: don't hold its stock again
        }
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        keys.add(StockKeys.reservation(storeId, orderId));
        // Redis TTL is a backstop slightly longer than the sweeper deadline.
        args.add(String.valueOf(ttl.plusMinutes(5).toSeconds()));
        for (OrderLine line : lines) {
            keys.add(StockKeys.stock(storeId, line.productId()));
            args.add(String.valueOf(line.quantity()));
        }
        long outcome = redis.execute(RESERVE, keys, args.toArray());
        if (outcome < 0) {
            return Result.OUT_OF_STOCK;
        }
        // Also runs when Redis says "already reserved": that is the retry after this step failed last time.
        tx.executeWithoutResult(s -> {
            if (holdStatus(orderId) == null) {
                jdbc.batchUpdate("""
                        INSERT INTO stock_reservations (order_id, store_id, product_id, quantity) VALUES (?, ?, ?, ?)
                        """, lines, lines.size(), (ps, line) -> {
                    ps.setObject(1, orderId);
                    ps.setObject(2, storeId);
                    ps.setObject(3, line.productId());
                    ps.setInt(4, line.quantity());
                });
                adjust("reserved_stock = reserved_stock + ?", storeId, lines);
            }
        });
        redis.opsForZSet().add(StockKeys.EXPIRY_INDEX, storeId + "|" + orderId,
                Instant.now().plus(ttl).toEpochMilli());
        return outcome == 2 ? Result.ALREADY_RESERVED : Result.RESERVED;
    }

    /** Compensation for cancelled / timed-out orders. Idempotent. */
    public void release(UUID orderId, UUID storeId) {
        tx.executeWithoutResult(s -> adjust("reserved_stock = reserved_stock - ?", storeId, settle(orderId, "RELEASED")));
        // After commit: if Redis fails here, the redelivery finds nothing HELD and only repeats this call.
        redis.execute(RELEASE, List.of(StockKeys.reservation(storeId, orderId)));
        redis.opsForZSet().remove(StockKeys.EXPIRY_INDEX, storeId + "|" + orderId);
    }

    /** Order paid: convert the hold into a real deduction. Idempotent. */
    public void confirm(UUID orderId, UUID storeId) {
        List<OrderLine> sold = tx.execute(s -> {
            List<OrderLine> held = settle(orderId, "CONFIRMED");
            adjust("available_stock = available_stock - ?, reserved_stock = reserved_stock - ?", storeId, held);
            return held;
        });
        if (sold.isEmpty()) {
            String status = holdStatus(orderId);
            if (!"CONFIRMED".equals(status)) {
                // The hold was released (or never made) before the payment landed, so these units may be resold.
                log.warn("Order {} paid without an active stock hold (status={})", orderId, status);
            }
        }
        redis.execute(CONFIRM, List.of(StockKeys.reservation(storeId, orderId)));
        redis.opsForZSet().remove(StockKeys.EXPIRY_INDEX, storeId + "|" + orderId);
    }

    /** Moves the order's HELD lines to {@code status} and returns them; empty if nothing was held. */
    private List<OrderLine> settle(UUID orderId, String status) {
        return jdbc.query("""
                UPDATE stock_reservations SET status = ?, updated_at = now()
                WHERE order_id = ? AND status = 'HELD'
                RETURNING product_id, quantity
                """, (rs, i) -> new OrderLine(rs.getObject(1, UUID.class), rs.getInt(2)), status, orderId);
    }

    private String holdStatus(UUID orderId) {
        return jdbc.query("SELECT status FROM stock_reservations WHERE order_id = ? LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, orderId);
    }

    /** Applies {@code setClause} per line; every '?' in it is bound to the line's quantity. */
    private void adjust(String setClause, UUID storeId, List<OrderLine> lines) {
        int quantityParams = (int) setClause.chars().filter(c -> c == '?').count();
        jdbc.batchUpdate("UPDATE store_inventories SET " + setClause + ", updated_at = now() "
                + "WHERE store_id = ? AND product_id = ?", lines, lines.size(), (ps, line) -> {
            for (int i = 1; i <= quantityParams; i++) {
                ps.setInt(i, line.quantity());
            }
            ps.setObject(quantityParams + 1, storeId);
            ps.setObject(quantityParams + 2, line.productId());
        });
    }

    /** One line per product: two lines for the same product must be checked against stock as one. */
    static List<OrderLine> merge(List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Order has no lines");
        }
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            if (line.productId() == null || line.quantity() <= 0) {
                throw new IllegalArgumentException("Invalid order line " + line);
            }
            quantities.merge(line.productId(), line.quantity(), Math::addExact);
        }
        return quantities.entrySet().stream().map(e -> new OrderLine(e.getKey(), e.getValue())).toList();
    }
}
