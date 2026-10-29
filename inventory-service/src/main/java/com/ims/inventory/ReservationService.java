package com.ims.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ims.common.events.OrderLine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two-phase stock deduction: Redis/Lua is the hot-path reservation barrier,
 * Postgres is updated behind it (write-behind) when holds change state.
 */
@Service
public class ReservationService {

    public enum Result { RESERVED, ALREADY_RESERVED, OUT_OF_STOCK }

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RELEASE = RedisScript.of(new ClassPathResource("scripts/release.lua"), List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CONFIRM = RedisScript.of(new ClassPathResource("scripts/confirm.lua"), List.class);
    private static final RedisScript<Long> RESERVE = RedisScript.of(new ClassPathResource("scripts/reserve.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final Duration ttl;

    ReservationService(StringRedisTemplate redis, JdbcTemplate jdbc, @Value("${ims.reservation-ttl}") Duration ttl) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.ttl = ttl;
    }

    @Transactional
    public Result reserve(UUID orderId, UUID storeId, List<OrderLine> lines) {
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
        if (outcome == 2) {
            return Result.ALREADY_RESERVED;
        }
        if (outcome < 0) {
            return Result.OUT_OF_STOCK;
        }
        redis.opsForZSet().add(StockKeys.EXPIRY_INDEX, storeId + "|" + orderId,
                Instant.now().plus(ttl).toEpochMilli());
        batchUpdate("reserved_stock = reserved_stock + ?", storeId, lines);
        return Result.RESERVED;
    }

    /** Compensation for cancelled / timed-out orders. Idempotent. */
    @Transactional
    public void release(UUID orderId, UUID storeId) {
        List<OrderLine> released = toLines(redis.execute(RELEASE, List.of(StockKeys.reservation(storeId, orderId))));
        redis.opsForZSet().remove(StockKeys.EXPIRY_INDEX, storeId + "|" + orderId);
        batchUpdate("reserved_stock = reserved_stock - ?", storeId, released);
    }

    /** Order paid: convert the hold into a real deduction. Idempotent. */
    @Transactional
    public void confirm(UUID orderId, UUID storeId) {
        List<OrderLine> sold = toLines(redis.execute(CONFIRM, List.of(StockKeys.reservation(storeId, orderId))));
        redis.opsForZSet().remove(StockKeys.EXPIRY_INDEX, storeId + "|" + orderId);
        jdbc.batchUpdate("""
                UPDATE store_inventories
                SET available_stock = available_stock - ?, reserved_stock = reserved_stock - ?, updated_at = now()
                WHERE store_id = ? AND product_id = ?
                """, sold, sold.size(), (ps, line) -> {
            ps.setInt(1, line.quantity());
            ps.setInt(2, line.quantity());
            ps.setObject(3, storeId);
            ps.setObject(4, line.productId());
        });
    }

    private void batchUpdate(String setClause, UUID storeId, List<OrderLine> lines) {
        jdbc.batchUpdate("UPDATE store_inventories SET " + setClause + ", updated_at = now() "
                + "WHERE store_id = ? AND product_id = ?", lines, lines.size(), (ps, line) -> {
            ps.setInt(1, line.quantity());
            ps.setObject(2, storeId);
            ps.setObject(3, line.productId());
        });
    }

    private static List<OrderLine> toLines(List<?> flat) {
        List<OrderLine> lines = new ArrayList<>();
        for (int i = 0; flat != null && i < flat.size(); i += 2) {
            lines.add(new OrderLine(StockKeys.productId((String) flat.get(i)),
                    Integer.parseInt((String) flat.get(i + 1))));
        }
        return lines;
    }
}
