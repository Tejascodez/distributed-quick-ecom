package com.ims.auth;

import java.util.Map;

import com.ims.common.security.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs user-auth-service against throwaway Postgres + Redis containers (never Neon) over real HTTP.
 * Containers start once per JVM and are shared by every test class, matching Spring's cached context
 * (per-class @Container lifecycles would leave that cached context pointing at stopped containers).
 * Requests are sent the way Envoy forwards them: the token is verified against the service's own JWKS,
 * then X-User-Id / X-User-Role are set from the verified claims.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("resource") // shared containers live for the whole JVM; the reaper stops them
abstract class IntegrationTestBase {

    static final String ADMIN_PHONE = "9000000001";

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.6.8-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start(); // stopped by Testcontainers' reaper when the JVM exits
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        String signingKey = JwtConfigTest.newPkcs8Key();
        registry.add("ims.jwt.private-key", () -> signingKey);
        registry.add("ims.bootstrap-admin-phone", () -> ADMIN_PHONE);
    }

    record Response(int status, JsonNode body) {
        String text(String field) {
            return body.path(field).asString();
        }
    }

    record Tokens(String access, String refresh) {
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    UserRepository users;

    RestClient http;
    NimbusJwtDecoder envoy;

    @BeforeEach
    void setUpClient() {
        http = RestClient.create("http://localhost:" + port);
        envoy = NimbusJwtDecoder.withJwkSetUri("http://localhost:" + port + "/.well-known/jwks.json").build();
    }

    /** Unauthenticated call (public routes). */
    Response call(HttpMethod method, String path, Object body) {
        return send(method, path, body, new HttpHeaders());
    }

    /** Authenticated call, forwarded like Envoy does after validating the token. */
    Response callAs(String accessToken, HttpMethod method, String path, Object body) {
        Jwt jwt = envoy.decode(accessToken);
        var headers = new HttpHeaders();
        headers.set(Caller.USER_ID_HEADER, jwt.getSubject());
        headers.set(Caller.ROLE_HEADER, jwt.getClaimAsString("role"));
        return send(method, path, body, headers);
    }

    private Response send(HttpMethod method, String path, Object body, HttpHeaders headers) {
        var spec = http.method(method).uri(path).headers(h -> h.addAll(headers));
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        var entity = spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
        String raw = entity.getBody();
        return new Response(entity.getStatusCode().value(),
                raw == null || raw.isBlank() ? json.createObjectNode() : json.readTree(raw));
    }

    static String randomPhone() {
        return "8" + String.format("%09d", (long) (Math.random() * 1_000_000_000L));
    }

    Tokens register(String phone) {
        Response r = call(HttpMethod.POST, "/api/v1/auth/register",
                Map.of("fullName", "Test User", "phone", phone, "password", "password123"));
        return new Tokens(r.text("accessToken"), r.text("refreshToken"));
    }

    Tokens register() {
        return register(randomPhone());
    }

    Tokens login(String phone) {
        Response r = call(HttpMethod.POST, "/api/v1/auth/login", Map.of("phone", phone, "password", "password123"));
        return new Tokens(r.text("accessToken"), r.text("refreshToken"));
    }

    Response refresh(String refreshToken) {
        return call(HttpMethod.POST, "/api/v1/auth/refresh", Map.of("refreshToken", refreshToken));
    }

    String userId(String accessToken) {
        return envoy.decode(accessToken).getSubject();
    }

    String role(String accessToken) {
        return envoy.decode(accessToken).getClaimAsString("role");
    }
}
