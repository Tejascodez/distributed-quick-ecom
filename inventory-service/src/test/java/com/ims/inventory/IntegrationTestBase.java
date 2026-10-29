package com.ims.inventory;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs inventory-service against throwaway Postgres, Redis and Kafka containers (never Neon).
 * Containers start once per JVM and are shared by every test class, matching Spring's cached context.
 * Tests use fresh store/product ids, so they don't need to clean up after each other.
 */
@SpringBootTest
@SuppressWarnings("resource") // shared containers live for the whole JVM; the reaper stops them
abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.6.8-alpine").withExposedPorts(6379);

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
    }

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JdbcTemplate jdbc;

    /** Puts {@code quantity} units on the shelf in Postgres and Redis, the way restock does. */
    void stock(UUID storeId, UUID productId, int quantity) {
        jdbc.update("INSERT INTO store_inventories (store_id, product_id, available_stock) VALUES (?, ?, ?)",
                storeId, productId, quantity);
        redis.opsForValue().set(StockKeys.stock(storeId, productId), String.valueOf(quantity));
    }

    long sellable(UUID storeId, UUID productId) {
        String value = redis.opsForValue().get(StockKeys.stock(storeId, productId));
        return value == null ? 0 : Long.parseLong(value);
    }

    int available(UUID storeId, UUID productId) {
        return column("available_stock", storeId, productId);
    }

    int reserved(UUID storeId, UUID productId) {
        return column("reserved_stock", storeId, productId);
    }

    String holdStatus(UUID orderId) {
        return jdbc.query("SELECT status FROM stock_reservations WHERE order_id = ? LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, orderId);
    }

    private int column(String name, UUID storeId, UUID productId) {
        return jdbc.queryForObject("SELECT " + name + " FROM store_inventories WHERE store_id = ? AND product_id = ?",
                Integer.class, storeId, productId);
    }
}
