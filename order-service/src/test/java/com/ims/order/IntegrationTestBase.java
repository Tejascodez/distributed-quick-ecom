package com.ims.order;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ims.common.events.Topics;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * Runs order-service against throwaway Postgres, Redis and Kafka containers (never Neon). Containers start once
 * per JVM and are shared by every test class, matching Spring's cached context. dark-store-service and
 * user-auth-service are mocked: every address resolves, every store delivers, every product costs 100.
 * Debezium isn't running, so tests read what the saga would publish straight from outbox_events.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("resource") // shared containers live for the whole JVM; the reaper stops them
abstract class IntegrationTestBase {

    static final String WEBHOOK_SECRET = "test-webhook-secret";
    static final BigDecimal UNIT_PRICE = new BigDecimal("100.00");

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
        createTopics();
    }

    /** Topics exist before the listeners subscribe, so the first test doesn't wait on auto-creation. */
    private static void createTopics() {
        List<String> names = List.of(Topics.INVENTORY_RESERVED, Topics.INVENTORY_REJECTED, Topics.PAYMENT_SUCCESS,
                Topics.PAYMENT_FAILED, Topics.INVENTORY_RESERVED + "-dlt", Topics.INVENTORY_REJECTED + "-dlt",
                Topics.PAYMENT_SUCCESS + "-dlt", Topics.PAYMENT_FAILED + "-dlt");
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(names.stream().map(n -> new NewTopic(n, 1, (short) 1)).toList()).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create test topics", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ims.payments.webhook-secret", () -> WEBHOOK_SECRET);
    }

    @MockitoBean
    CatalogClient catalog;

    @MockitoBean
    AddressClient addresses;

    /** A spy so a test can make one call fail; it runs the real methods otherwise. */
    @MockitoSpyBean
    OrderService orderService;

    @Autowired
    OrderRepository orders;

    @Autowired
    JdbcTemplate jdbc;

    final UUID user = UUID.randomUUID();
    final UUID store = UUID.randomUUID();
    final UUID address = UUID.randomUUID();
    final UUID milk = UUID.randomUUID();
    /** Suffix for gateway references: they are unique across the whole database, like real ones. */
    final String run = "_" + UUID.randomUUID();

    @BeforeEach
    void stubDownstreamServices() {
        given(addresses.find(any(), eq(address)))
                .willAnswer(inv -> Optional.of(new AddressClient.AddressLocation(address, inv.getArgument(0), 12.97, 77.59)));
        given(catalog.deliversTo(any(), anyDouble(), anyDouble())).willReturn(true);
        given(catalog.prices(any())).willAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            return ids.stream().map(id -> new CatalogClient.ProductPrice(id, "Item " + id, UNIT_PRICE)).toList();
        });
    }

    /** A 2 x 100 order: 200 of items qualifies for free delivery, so 200 is payable. */
    Order newOrder() {
        return orderService.create(user, store, address, List.of(new OrderService.CheckoutLine(milk, 2)),
                user + ":" + UUID.randomUUID());
    }

    Order reload(Order order) {
        return orders.findById(order.getId()).orElseThrow();
    }

    /** Outbox payloads for this order on {@code topic}, oldest first. */
    List<String> published(Order order, String topic) {
        return jdbc.queryForList("""
                SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND event_type = ? ORDER BY created_at
                """, String.class, order.getId().toString(), topic);
    }
}
