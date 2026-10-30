package com.ims.notification;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.ims.common.events.OrderPaidEvent;
import com.ims.common.events.OrderStatusChangedEvent;
import com.ims.common.events.RiderAssignedEvent;
import com.ims.common.events.Topics;
import com.ims.common.security.Roles;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Kafka events and Redis rider pings in, STOMP messages out to the right subscribers. */
class RelayTest extends IntegrationTestBase {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    final UUID order = UUID.randomUUID();
    final UUID owner = UUID.randomUUID();
    final UUID store = UUID.randomUUID();

    @Test
    void statusChangeReachesTheCustomerAndTeachesWhoOwnsTheOrder() throws InterruptedException {
        send(Topics.ORDER_STATUS_CHANGED, new OrderStatusChangedEvent(order, owner, "INVENTORY_RESERVED"));
        await().atMost(TIMEOUT).until(() -> redis.opsForValue().get(OrderOwners.key(order)) != null);

        BlockingQueue<String> updates = subscribed(connect(owner, Roles.CUSTOMER), orderTopic(order));
        send(Topics.ORDER_STATUS_CHANGED, new OrderStatusChangedEvent(order, owner, "PAID"));

        assertThat(updates.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).contains("\"PAID\"");
        verify(orderClient, never()).canRead(any(), any());
    }

    @Test
    void paidOrderReachesItsStoresPickers() throws InterruptedException {
        BlockingQueue<String> pickers = subscribed(connect(UUID.randomUUID(), Roles.STORE_OPS),
                "/topic/stores/" + store + "/picking");

        send(Topics.ORDER_PAID, new OrderPaidEvent(order, owner, store));

        assertThat(pickers.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).contains(order.toString());
        await().atMost(TIMEOUT).until(() -> owner.toString().equals(redis.opsForValue().get(OrderOwners.key(order))));
    }

    @Test
    void riderAssignmentReachesTheCustomer() throws InterruptedException {
        new OrderOwners(redis, orderClient).remember(order, owner);
        BlockingQueue<String> updates = subscribed(connect(owner, Roles.CUSTOMER), orderTopic(order));
        UUID rider = UUID.randomUUID();

        send(Topics.RIDER_ASSIGNED, new RiderAssignedEvent(order, rider, store, 8));

        assertThat(updates.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).contains(rider.toString());
    }

    @Test
    void riderPingIsRelayedToTheCustomersMap() throws InterruptedException {
        new OrderOwners(redis, orderClient).remember(order, owner);
        BlockingQueue<String> positions = subscribed(connect(owner, Roles.CUSTOMER), orderTopic(order) + "/rider");

        redis.convertAndSend("rider-location:" + order, "{\"lat\":12.9,\"lng\":77.6}");

        assertThat(positions.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("{\"lat\":12.9,\"lng\":77.6}");
    }

    @Test
    void malformedEventIsDeadLettered() {
        kafka.send(Topics.ORDER_STATUS_CHANGED, order.toString(), "{not json").join();

        await().atMost(TIMEOUT).until(() -> dltHasRecordFor(Topics.ORDER_STATUS_CHANGED + "-dlt"));
    }

    private void send(String topic, Object event) {
        kafka.send(topic, order.toString(), json.writeValueAsString(event)).join();
    }

    private boolean dltHasRecordFor(String topic) {
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
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
