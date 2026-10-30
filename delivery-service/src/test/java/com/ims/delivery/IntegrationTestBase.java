package com.ims.delivery;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.ims.common.events.Topics;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Runs delivery-service against throwaway Postgres, Redis and Kafka containers (never Neon), with
 * dark-store-service mocked. The scheduled drain and pool maintenance keep running during tests, so each test
 * puts its store at a random spot on the globe: riders from one test are never in range of another's store.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("resource") // shared containers live for the whole JVM; the reaper stops them
abstract class IntegrationTestBase {

    /** About 111 km per degree of latitude. */
    static final double KM_PER_DEGREE = 111.0;

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.6.8-alpine").withExposedPorts(6379);

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

    /** Where each test store is; a store missing from here makes the mocked lookup fail. */
    static final Map<UUID, StoreClient.StoreLocation> STORES = new ConcurrentHashMap<>();

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
        createTopics();
    }

    /** Topics exist before the listeners subscribe, so the first test doesn't wait on auto-creation. */
    private static void createTopics() {
        List<String> names = List.of(Topics.ORDER_PAID, Topics.ORDER_CANCELLED, Topics.RIDER_ASSIGNED,
                Topics.DELIVERY_PROGRESS, Topics.ORDER_PAID + "-dlt", Topics.ORDER_CANCELLED + "-dlt");
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(names.stream().map(n -> new NewTopic(n, 1, (short) 1)).toList()).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create test topics", e);
        }
    }

    @MockitoBean
    StoreClient stores;

    @Autowired
    DispatchService dispatch;

    @Autowired
    RiderPool pool;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JdbcTemplate jdbc;

    final UUID store = UUID.randomUUID();
    double storeLat;
    double storeLng;

    @BeforeEach
    void placeStore() {
        storeLat = ThreadLocalRandom.current().nextDouble(-60, 60);
        storeLng = ThreadLocalRandom.current().nextDouble(-170, 170);
        STORES.put(store, new StoreClient.StoreLocation(store, storeLat, storeLng));
        given(stores.location(any())).willAnswer(inv -> {
            var location = STORES.get(inv.<UUID>getArgument(0));
            if (location == null) {
                throw new IllegalStateException("dark-store-service unavailable");
            }
            return location;
        });
    }

    /** Registers a rider, puts them online and pings from {@code km} north of the store. */
    UUID onlineRider(double km) {
        UUID rider = UUID.randomUUID();
        jdbc.update("INSERT INTO delivery_partners (id, full_name, phone, vehicle_type, is_online) VALUES (?, ?, ?, 'BIKE', TRUE)",
                rider, "Rider " + rider, randomPhone());
        pool.online(rider);
        ping(rider, km);
        return rider;
    }

    UUID ping(UUID rider, double km) {
        return pool.ping(rider, storeLat + km / KM_PER_DEGREE, storeLng);
    }

    boolean idle(UUID rider) {
        return redis.opsForZSet().score(RiderKeys.IDLE, rider.toString()) != null;
    }

    String marker(UUID rider) {
        return redis.opsForValue().get(RiderKeys.activeOrder(rider));
    }

    /** The rider of the order's open assignment, or null. */
    UUID assignedRider(UUID order) {
        return jdbc.query("""
                SELECT partner_id FROM delivery_assignments
                WHERE order_id = ? AND delivered_at IS NULL AND cancelled_at IS NULL
                """, (rs, i) -> rs.getObject(1, UUID.class), order).stream().findFirst().orElse(null);
    }

    String partnerStatus(UUID rider) {
        return jdbc.queryForObject("SELECT current_status FROM delivery_partners WHERE id = ?", String.class, rider);
    }

    static String randomPhone() {
        return "7" + String.format("%09d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
    }
}
