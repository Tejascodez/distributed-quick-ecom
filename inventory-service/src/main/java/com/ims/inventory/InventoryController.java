package com.ims.inventory;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import com.ims.common.security.Caller;
import com.ims.common.security.Roles;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
class InventoryController {

    record RestockRequest(@Positive int quantity, String shelfBinLocation) {
    }

    record StockView(UUID storeId, UUID productId, long sellable) {
    }

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    InventoryController(StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    @GetMapping("/{storeId}/{productId}")
    StockView get(@PathVariable UUID storeId, @PathVariable UUID productId) {
        String value = redis.opsForValue().get(StockKeys.stock(storeId, productId));
        return new StockView(storeId, productId, value == null ? 0 : Long.parseLong(value));
    }

    /**
     * Not @Transactional on purpose: the upsert commits on its own before Redis is incremented. If Redis fails
     * afterwards it under-counts (safe; reconciliation tops it up), never selling units Postgres doesn't have.
     */
    @PostMapping("/{storeId}/{productId}/restock")
    StockView restock(Caller caller, @PathVariable UUID storeId, @PathVariable UUID productId,
                      @Valid @RequestBody RestockRequest req) {
        caller.requireAnyRole(Roles.ADMIN, Roles.STORE_OPS);
        jdbc.update("""
                INSERT INTO store_inventories (store_id, product_id, available_stock, shelf_bin_location)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (store_id, product_id) DO UPDATE
                SET available_stock = store_inventories.available_stock + EXCLUDED.available_stock,
                    shelf_bin_location = COALESCE(EXCLUDED.shelf_bin_location, store_inventories.shelf_bin_location),
                    updated_at = now()
                """, storeId, productId, req.quantity(), req.shelfBinLocation());
        Long sellable = redis.opsForValue().increment(StockKeys.stock(storeId, productId), req.quantity());
        return new StockView(storeId, productId, sellable == null ? 0 : sellable);
    }

    /** Seeds Redis counters from Postgres. SETNX keeps live counters intact across restarts. */
    @EventListener(ApplicationReadyEvent.class)
    void warmCache() {
        jdbc.query("SELECT store_id, product_id, available_stock - reserved_stock AS sellable FROM store_inventories",
                rs -> {
                    redis.opsForValue().setIfAbsent(
                            StockKeys.stock(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                            String.valueOf(rs.getInt(3)));
                });
    }
}
