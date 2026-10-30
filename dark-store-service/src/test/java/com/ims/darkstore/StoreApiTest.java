package com.ims.darkstore;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import com.ims.common.security.Caller;
import com.ims.common.security.Roles;
import com.uber.h3core.H3Core;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Store lookup, status and catalog endpoints over real HTTP against a throwaway PostGIS container (never Neon),
 * sent the way Envoy forwards them. Stores from every test share one table, so each test works around its own
 * random spot on the globe, far from the others.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("resource") // the shared container lives for the whole JVM; the reaper stops it
class StoreApiTest {

    /** About 111 km per degree of latitude. */
    static final double KM_PER_DEGREE = 111.0;

    /** A multi-arch PostGIS build (the official postgis/postgis images are amd64 only). */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("imresamu/postgis:18-3.6-alpine").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    H3Core h3;

    RestClient http;
    double lat;
    double lng;

    @BeforeEach
    void setUp() {
        http = RestClient.create("http://localhost:" + port);
        lat = ThreadLocalRandom.current().nextDouble(-60, 60);
        lng = ThreadLocalRandom.current().nextDouble(-170, 170);
    }

    @Test
    void createdStoreGetsItsH3Cell() {
        JsonNode store = body(createStore(0, 3000));

        assertThat(store.get("h3Cell").asString()).isEqualTo(Long.toHexString(h3.latLngToCell(lat, lng, 8)));
        assertThat(store.get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void customerIsServedByTheNearestStoreThatCoversThem() {
        String near = id(createStore(0, 3000));
        createStore(2, 3000);

        assertThat(body(serviceable(0.5)).get("id").asString()).isEqualTo(near);
    }

    @Test
    void customerOutsideEveryRadiusIsNotServiceable() {
        createStore(0, 3000);

        assertThat(serviceable(5).getStatusCode().value()).isEqualTo(404);
    }

    /** Areas overlap: a farther store can still deliver to a point, even though it isn't the nearest. */
    @Test
    void fartherStoreWhoseAreaOverlapsStillDelivers() {
        createStore(0, 3000);
        String far = id(createStore(4, 3000));

        assertThat(deliversTo(far, 1.5)).isTrue();
        assertThat(deliversTo(far, -0.5)).isFalse(); // 4.5 km away
    }

    @Test
    void overloadedStoreStopsTakingOrdersUntilReactivated() {
        String store = id(createStore(0, 3000));
        var before = jdbc.queryForObject("SELECT updated_at FROM dark_stores WHERE id = ?::uuid",
                Timestamp.class, store);

        assertThat(setStatus(store, "OVERLOADED", Roles.STORE_OPS).getStatusCode().value()).isEqualTo(200);

        assertThat(serviceable(0.5).getStatusCode().value()).isEqualTo(404);
        assertThat(deliversTo(store, 0.5)).isFalse();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM dark_stores WHERE id = ?::uuid",
                Timestamp.class, store)).isAfter(before);

        setStatus(store, "ACTIVE", Roles.ADMIN);
        assertThat(body(serviceable(0.5)).get("id").asString()).isEqualTo(store);
    }

    @Test
    void customerCannotChangeAStoresStatus() {
        String store = id(createStore(0, 3000));

        assertThat(setStatus(store, "INACTIVE", Roles.CUSTOMER).getStatusCode().value()).isEqualTo(403);
        assertThat(deliversTo(store, 0.5)).isTrue();
    }

    @Test
    void unknownStoreStatusChangeIsNotFound() {
        assertThat(setStatus(UUID.randomUUID().toString(), "INACTIVE", Roles.ADMIN).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void impossibleCoordinatesAreRejected() {
        assertThat(post(Map.of("name", "S", "address", "A", "lat", 91.0, "lng", 10.0, "serviceableRadiusMeters", 3000))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(post(Map.of("name", "S", "address", "A", "lat", 10.0, "lng", 181.0, "serviceableRadiusMeters", 3000))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(get("/api/v1/stores/serviceable?lat=91&lng=0").getStatusCode().value()).isEqualTo(400);
        assertThat(get("/api/v1/stores/" + UUID.randomUUID() + "/delivers-to?lat=0&lng=-181").getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void absurdRadiusIsRejected() {
        assertThat(createStore(0, StoreController.MAX_RADIUS_METERS + 1).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void productLookupReturnsPricesAndIsCapped() {
        UUID milk = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, sku, name, base_price, unit) VALUES (?, ?, 'Milk', ?, 'ml')",
                milk, "SKU-" + milk, new BigDecimal("30.00"));
        List<String> tooMany = IntStream.range(0, StoreController.MAX_PRODUCTS_PER_LOOKUP + 1)
                .mapToObj(i -> UUID.randomUUID().toString()).toList();

        JsonNode found = body(get("/api/v1/stores/products?ids=" + milk + "," + UUID.randomUUID()));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("basePrice").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(get("/api/v1/stores/products?ids=" + String.join(",", tooMany)).getStatusCode().value())
                .isEqualTo(400);
    }

    /** A store {@code km} north of this test's spot. */
    private ResponseEntity<String> createStore(double km, int radiusMeters) {
        return post(Map.of("name", "Store", "address", "Somewhere", "lat", lat + km / KM_PER_DEGREE, "lng", lng,
                "serviceableRadiusMeters", radiusMeters));
    }

    private ResponseEntity<String> serviceable(double km) {
        return get("/api/v1/stores/serviceable?lat=" + (lat + km / KM_PER_DEGREE) + "&lng=" + lng);
    }

    private boolean deliversTo(String store, double km) {
        return body(get("/api/v1/stores/" + store + "/delivers-to?lat=" + (lat + km / KM_PER_DEGREE) + "&lng=" + lng))
                .get("delivers").asBoolean();
    }

    private ResponseEntity<String> post(Object body) {
        return http.post().uri("/api/v1/stores").headers(h -> as(h, Roles.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private ResponseEntity<String> setStatus(String store, String status, String role) {
        return http.patch().uri("/api/v1/stores/{id}/status", store).headers(h -> as(h, role))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("status", status))
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private ResponseEntity<String> get(String uri) {
        return http.get().uri(uri).headers(h -> as(h, Roles.CUSTOMER))
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private static void as(HttpHeaders headers, String role) {
        headers.add(Caller.USER_ID_HEADER, UUID.randomUUID().toString());
        headers.add(Caller.ROLE_HEADER, role);
    }

    private JsonNode body(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        return json.readTree(response.getBody());
    }

    private String id(ResponseEntity<String> response) {
        return body(response).get("id").asString();
    }
}
