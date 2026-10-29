package com.ims.inventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ims.common.events.InventoryResultEvent;
import com.ims.common.events.OrderCancelledEvent;
import com.ims.common.events.OrderCreatedEvent;
import com.ims.common.events.OrderLine;
import com.ims.common.events.OrderPaidEvent;
import com.ims.common.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The saga as order-service drives it: events in over Kafka, replies and stock changes out. */
class KafkaFlowTest extends IntegrationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    final UUID store = UUID.randomUUID();
    final UUID milk = UUID.randomUUID();
    final UUID order = UUID.randomUUID();
    final UUID user = UUID.randomUUID();

    @Test
    void reservedThenPaidDeductsStock() {
        stock(store, milk, 5);

        send(Topics.ORDER_CREATED, created(2));

        var reply = json.readValue(awaitRecord(Topics.INVENTORY_RESERVED).value(), InventoryResultEvent.class);
        assertThat(reply).isEqualTo(new InventoryResultEvent(order, store, true, null));
        assertThat(sellable(store, milk)).isEqualTo(3);

        send(Topics.ORDER_PAID, new OrderPaidEvent(order, user, store));

        await().atMost(TIMEOUT).until(() -> "CONFIRMED".equals(holdStatus(order)));
        assertThat(available(store, milk)).isEqualTo(3);
        assertThat(reserved(store, milk)).isZero();
    }

    @Test
    void outOfStockIsRejected() {
        stock(store, milk, 1);

        send(Topics.ORDER_CREATED, created(2));

        var reply = json.readValue(awaitRecord(Topics.INVENTORY_REJECTED).value(), InventoryResultEvent.class);
        assertThat(reply).isEqualTo(new InventoryResultEvent(order, store, false, "OUT_OF_STOCK"));
        assertThat(sellable(store, milk)).isEqualTo(1);
    }

    @Test
    void cancelledOrderReturnsItsStock() {
        stock(store, milk, 5);
        send(Topics.ORDER_CREATED, created(2));
        awaitRecord(Topics.INVENTORY_RESERVED);

        send(Topics.ORDER_CANCELLED, new OrderCancelledEvent(order, store, List.of(new OrderLine(milk, 2)), "USER"));

        await().atMost(TIMEOUT).until(() -> "RELEASED".equals(holdStatus(order)));
        assertThat(sellable(store, milk)).isEqualTo(5);
        assertThat(reserved(store, milk)).isZero();
    }

    @Test
    void redeliveredOrderCreatedRepliesAgainButHoldsOnce() {
        stock(store, milk, 5);

        send(Topics.ORDER_CREATED, created(2));
        send(Topics.ORDER_CREATED, created(2));

        await().atMost(TIMEOUT).until(() -> records(Topics.INVENTORY_RESERVED).size() == 2);
        assertThat(sellable(store, milk)).isEqualTo(3);
        assertThat(reserved(store, milk)).isEqualTo(2);
    }

    @Test
    void malformedPayloadGoesToTheDeadLetterTopic() {
        kafka.send(Topics.ORDER_CREATED, order.toString(), "{not json").join();

        assertThat(awaitRecord(Topics.ORDER_CREATED + "-dlt").value()).isEqualTo("{not json");
    }

    @Test
    void invalidOrderGoesToTheDeadLetterTopicWithoutHoldingStock() {
        stock(store, milk, 5);

        send(Topics.ORDER_CREATED, created(0));

        awaitRecord(Topics.ORDER_CREATED + "-dlt");
        assertThat(sellable(store, milk)).isEqualTo(5);
        assertThat(records(Topics.INVENTORY_RESERVED)).isEmpty();
        assertThat(records(Topics.INVENTORY_REJECTED)).isEmpty();
    }

    private OrderCreatedEvent created(int quantity) {
        return new OrderCreatedEvent(order, user, store, List.of(new OrderLine(milk, quantity)), null);
    }

    private void send(String topic, Object event) {
        kafka.send(topic, order.toString(), json.writeValueAsString(event)).join();
    }

    private ConsumerRecord<String, String> awaitRecord(String topic) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        await().atMost(TIMEOUT).until(() -> {
            found.addAll(records(topic));
            return !found.isEmpty();
        });
        return found.getFirst();
    }

    /** Every record on {@code topic} keyed by this test's order id, read from the beginning. */
    private List<ConsumerRecord<String, String>> records(String topic) {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> matches = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (order.toString().equals(record.key())) {
                        matches.add(record);
                    }
                }
            }
            return matches;
        }
    }
}
