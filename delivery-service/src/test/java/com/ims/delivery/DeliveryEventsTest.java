package com.ims.delivery;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ims.common.events.DeliveryProgressEvent;
import com.ims.common.events.DeliveryProgressEvent.Stage;
import com.ims.common.events.OrderCancelledEvent;
import com.ims.common.events.OrderPaidEvent;
import com.ims.common.events.RiderAssignedEvent;
import com.ims.common.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** order-paid / order-cancelled in over real Kafka, rider-assigned out, unprocessable records dead-lettered. */
class DeliveryEventsTest extends IntegrationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @Autowired
    ProgressPublisher progress;

    final UUID order = UUID.randomUUID();

    @Test
    void paidOrderGetsARiderAndTheCustomerIsTold() {
        UUID rider = onlineRider(0.5);

        send(Topics.ORDER_PAID, new OrderPaidEvent(order, UUID.randomUUID(), store));

        await().atMost(TIMEOUT).until(() -> rider.equals(assignedRider(order)));
        String assigned = await().atMost(TIMEOUT).until(() -> firstRecord(Topics.RIDER_ASSIGNED), v -> v != null);
        assertThat(json.readValue(assigned, RiderAssignedEvent.class).partnerId()).isEqualTo(rider);
    }

    @Test
    void cancelledOrderFreesItsRider() {
        UUID rider = onlineRider(0.5);
        dispatch.tryAssign(order, store);

        send(Topics.ORDER_CANCELLED, new OrderCancelledEvent(order, store, List.of(), "CANCELLED_BY_OPS"));

        await().atMost(TIMEOUT).until(() -> marker(rider) == null);
        assertThat(assignedRider(order)).isNull();
    }

    @Test
    void cancelThatOvertakesOrderPaidPreventsDispatch() {
        UUID rider = onlineRider(0.5);

        send(Topics.ORDER_CANCELLED, new OrderCancelledEvent(order, store, List.of(), "CHECKOUT_TIMEOUT"));
        await().atMost(TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM cancelled_orders WHERE order_id = ?)", Boolean.class, order));
        send(Topics.ORDER_PAID, new OrderPaidEvent(order, UUID.randomUUID(), store));

        // Must hold across a full drain cycle (every 5s) after the late order-paid is queued.
        await().during(Duration.ofSeconds(7)).atMost(Duration.ofSeconds(10)).until(() -> assignedRider(order) == null);
        assertThat(idle(rider)).isTrue();
    }

    /** A pickup whose send never reached the broker (crash, outage) is resent and then marked published. */
    @Test
    void unsentPickupIsRepublished() {
        UUID rider = onlineRider(0.5);
        dispatch.tryAssign(order, store);
        jdbc.update("UPDATE delivery_assignments SET picked_up_at = now() - interval '1 minute' WHERE order_id = ?", order);

        progress.republishUnsent();

        String sent = await().atMost(TIMEOUT).until(() -> firstRecord(Topics.DELIVERY_PROGRESS), v -> v != null);
        assertThat(json.readValue(sent, DeliveryProgressEvent.class))
                .isEqualTo(new DeliveryProgressEvent(order, rider, Stage.PICKED_UP));
        await().atMost(TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT picked_up_published_at IS NOT NULL FROM delivery_assignments WHERE order_id = ?",
                Boolean.class, order));
    }

    @Test
    void malformedEventIsDeadLettered() {
        kafka.send(Topics.ORDER_PAID, order.toString(), "{not json").join();

        await().atMost(TIMEOUT).until(() -> firstRecord(Topics.ORDER_PAID + "-dlt"), v -> v != null);
    }

    private void send(String topic, Object event) {
        kafka.send(topic, order.toString(), json.writeValueAsString(event)).join();
    }

    /** The first record on {@code topic} keyed by this test's order, or null. */
    private String firstRecord(String topic) {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (order.toString().equals(record.key())) {
                        return record.value();
                    }
                }
            }
            return null;
        }
    }
}
