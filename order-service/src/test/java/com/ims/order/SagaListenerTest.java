package com.ims.order;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ims.common.events.DeliveryProgressEvent;
import com.ims.common.events.DeliveryProgressEvent.Stage;
import com.ims.common.events.InventoryResultEvent;
import com.ims.common.events.PaymentResultEvent;
import com.ims.common.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.awaitility.Awaitility.await;

/** Saga replies arriving over real Kafka, including records that can never be processed. */
class SagaListenerTest extends IntegrationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @Test
    void repliesOverKafkaPayTheOrder() {
        Order order = newOrder();

        send(Topics.INVENTORY_RESERVED, order.getId(), new InventoryResultEvent(order.getId(), store, true, null));
        send(Topics.PAYMENT_SUCCESS, order.getId(),
                new PaymentResultEvent(order.getId(), "pay_1" + run, true, OrderSagaTest.PAYABLE));

        await().atMost(TIMEOUT).until(() -> reload(order).getOrderStatus() == OrderStatus.PAID);
    }

    @Test
    void riderHandOverOverKafkaDeliversTheOrder() {
        Order order = newOrder();
        orderService.onInventoryReserved(order.getId());
        orderService.onPaymentSucceeded(order.getId(), "pay_1" + run, OrderSagaTest.PAYABLE);
        UUID rider = UUID.randomUUID();

        send(Topics.DELIVERY_PROGRESS, order.getId(), new DeliveryProgressEvent(order.getId(), rider, Stage.PICKED_UP));
        send(Topics.DELIVERY_PROGRESS, order.getId(), new DeliveryProgressEvent(order.getId(), rider, Stage.DELIVERED));

        await().atMost(TIMEOUT).until(() -> reload(order).getOrderStatus() == OrderStatus.DELIVERED);
    }

    @Test
    void pickupOfAnUnpaidOrderIsDeadLettered() {
        Order order = newOrder();

        send(Topics.DELIVERY_PROGRESS, order.getId(),
                new DeliveryProgressEvent(order.getId(), UUID.randomUUID(), Stage.PICKED_UP));

        await().atMost(TIMEOUT).until(() -> deadLettered(Topics.DELIVERY_PROGRESS, order.getId()));
    }

    @Test
    void replyForAnUnknownOrderIsDeadLettered() {
        UUID unknown = UUID.randomUUID();

        send(Topics.PAYMENT_SUCCESS, unknown,
                new PaymentResultEvent(unknown, "pay_1" + run, true, OrderSagaTest.PAYABLE));

        await().atMost(TIMEOUT).until(() -> deadLettered(Topics.PAYMENT_SUCCESS, unknown));
    }

    @Test
    void malformedReplyIsDeadLettered() {
        UUID key = UUID.randomUUID();

        kafka.send(Topics.INVENTORY_RESERVED, key.toString(), "{not json").join();

        await().atMost(TIMEOUT).until(() -> deadLettered(Topics.INVENTORY_RESERVED, key));
    }

    private void send(String topic, UUID key, Object event) {
        kafka.send(topic, key.toString(), json.writeValueAsString(event)).join();
    }

    private boolean deadLettered(String topic, UUID key) {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic + "-dlt"));
            long deadline = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (key.toString().equals(record.key())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
