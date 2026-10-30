package com.ims.order;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.ims.common.events.Topics;
import com.ims.common.security.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Checkout idempotency, ops status changes and the payment webhook over real HTTP, sent the way Envoy forwards them. */
class HttpApiTest extends IntegrationTestBase {

    @Value("${local.server.port}")
    int port;

    @Autowired
    WebhookSignatureVerifier verifier;

    @Autowired
    JsonMapper json;

    RestClient http;

    @BeforeEach
    void setUpClient() {
        http = RestClient.create("http://localhost:" + port);
    }

    @Test
    void fiftyParallelRetriesOfOneCheckoutCreateOneOrder() throws Exception {
        String key = UUID.randomUUID().toString();
        var pool = Executors.newFixedThreadPool(50);
        var start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> responses = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            responses.add(pool.submit(() -> {
                start.await();
                return checkout(key);
            }));
        }
        start.countDown();
        pool.shutdown();
        List<Integer> statuses = new ArrayList<>();
        for (var response : responses) {
            statuses.add(response.get().getStatusCode().value());
        }

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).allMatch(s -> s == 201 || s == 200 || s == 409); // 409 = still in progress
        assertThat(orders.findByIdempotencyKey(user + ":" + key)).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE idempotency_key = ?", Integer.class,
                user + ":" + key)).isEqualTo(1);
    }

    @Test
    void retryAfterCompletionReturnsTheSameOrder() {
        String key = UUID.randomUUID().toString();

        var first = checkout(key);
        var retry = checkout(key);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(retry.getBody()).path("id").asString())
                .isEqualTo(json.readTree(first.getBody()).path("id").asString());
    }

    @Test
    void sameKeyFromAnotherUserIsADifferentOrder() {
        String key = UUID.randomUUID().toString();
        checkout(key);

        var other = http.post().uri("/api/v1/orders")
                .header(Caller.USER_ID_HEADER, UUID.randomUUID().toString()).header(Caller.ROLE_HEADER, "CUSTOMER")
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).body(cart())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);

        assertThat(other.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void signedWebhookIsQueuedForTheSagaWithItsAmount() {
        Order order = newOrder();
        String body = webhookBody(order.getId(), true, "200.00");

        var response = webhook(body, String.valueOf(Instant.now().getEpochSecond()), null);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(published(order, Topics.PAYMENT_SUCCESS)).singleElement()
                .satisfies(event -> assertThat(event).contains("\"amount\": 200.00", "pay_" + order.getId()));
    }

    @Test
    void webhookWithABadSignatureIsRejected() {
        Order order = newOrder();
        String body = webhookBody(order.getId(), true, "200.00");

        var response = webhook(body, String.valueOf(Instant.now().getEpochSecond()), "00ff");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(published(order, Topics.PAYMENT_SUCCESS)).isEmpty();
    }

    @Test
    void replayedWebhookOutsideTheToleranceIsRejected() {
        Order order = newOrder();
        String body = webhookBody(order.getId(), true, "200.00");

        var response = webhook(body, String.valueOf(Instant.now().minusSeconds(600).getEpochSecond()), null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void successfulWebhookWithoutAnAmountIsRejected() {
        Order order = newOrder();
        String body = "{\"orderId\":\"" + order.getId() + "\",\"gatewayReference\":\"pay_1\",\"success\":true}";

        var response = webhook(body, String.valueOf(Instant.now().getEpochSecond()), null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(published(order, Topics.PAYMENT_SUCCESS)).isEmpty();
    }

    @Test
    void failedWebhookNeedsNoAmount() {
        Order order = newOrder();
        String body = "{\"orderId\":\"" + order.getId() + "\",\"gatewayReference\":\"pay_1\",\"success\":false}";

        var response = webhook(body, String.valueOf(Instant.now().getEpochSecond()), null);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(published(order, Topics.PAYMENT_FAILED)).hasSize(1);
    }

    @Test
    void opsCannotSetDeliveryStatusesByHand() {
        Order order = newOrder();
        orderService.onInventoryReserved(order.getId());
        orderService.onPaymentSucceeded(order.getId(), "pay_1" + run, OrderSagaTest.PAYABLE);

        assertThat(setStatus(order, "OUT_FOR_DELIVERY").getStatusCode().value()).isEqualTo(400);
        assertThat(setStatus(order, "DELIVERED").getStatusCode().value()).isEqualTo(400);
        assertThat(setStatus(order, "PICKING").getStatusCode().value()).isEqualTo(200);
        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.PICKING);
    }

    private ResponseEntity<String> setStatus(Order order, String status) {
        return http.patch().uri("/api/v1/orders/{id}/status", order.getId())
                .header(Caller.USER_ID_HEADER, UUID.randomUUID().toString()).header(Caller.ROLE_HEADER, "STORE_OPS")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("status", status))
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private ResponseEntity<String> checkout(String idempotencyKey) {
        return http.post().uri("/api/v1/orders")
                .header(Caller.USER_ID_HEADER, user.toString()).header(Caller.ROLE_HEADER, "CUSTOMER")
                .header("Idempotency-Key", idempotencyKey).contentType(MediaType.APPLICATION_JSON).body(cart())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private Map<String, Object> cart() {
        return Map.of("storeId", store, "deliveryAddressId", address,
                "items", List.of(Map.of("productId", milk, "quantity", 2)));
    }

    private static String webhookBody(UUID orderId, boolean success, String amount) {
        return "{\"orderId\":\"" + orderId + "\",\"gatewayReference\":\"pay_" + orderId + "\",\"success\":" + success
                + ",\"amount\":" + amount + "}";
    }

    /** Signs like the gateway does unless {@code signature} is given. */
    private ResponseEntity<String> webhook(String body, String timestamp, String signature) {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        String sig = signature != null ? signature : HexFormat.of().formatHex(verifier.sign(timestamp, raw));
        return http.post().uri("/api/v1/payments/webhook")
                .header("X-Webhook-Timestamp", timestamp).header("X-Webhook-Signature", sig)
                .contentType(MediaType.APPLICATION_JSON).body(raw)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }
}
