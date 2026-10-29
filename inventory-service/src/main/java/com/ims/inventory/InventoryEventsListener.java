package com.ims.inventory;

import com.ims.common.events.InventoryResultEvent;
import com.ims.common.events.OrderCancelledEvent;
import com.ims.common.events.OrderCreatedEvent;
import com.ims.common.events.OrderPaidEvent;
import com.ims.common.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class InventoryEventsListener {

    private final ReservationService reservations;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    InventoryEventsListener(ReservationService reservations, KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.reservations = reservations;
        this.kafka = kafka;
        this.json = json;
    }

    @KafkaListener(topics = Topics.ORDER_CREATED)
    void onOrderCreated(String payload) {
        var event = json.readValue(payload, OrderCreatedEvent.class);
        var result = reservations.reserve(event.orderId(), event.storeId(), event.items());
        boolean ok = result != ReservationService.Result.OUT_OF_STOCK;
        var reply = new InventoryResultEvent(event.orderId(), event.storeId(), ok, ok ? null : "OUT_OF_STOCK");
        // Block until the broker acks: a failed send throws, Kafka redelivers, and the idempotent reserve replies again.
        kafka.send(ok ? Topics.INVENTORY_RESERVED : Topics.INVENTORY_REJECTED,
                event.orderId().toString(), json.writeValueAsString(reply)).join();
    }

    @KafkaListener(topics = Topics.ORDER_PAID)
    void onOrderPaid(String payload) {
        var event = json.readValue(payload, OrderPaidEvent.class);
        reservations.confirm(event.orderId(), event.storeId());
    }

    @KafkaListener(topics = Topics.ORDER_CANCELLED)
    void onOrderCancelled(String payload) {
        var event = json.readValue(payload, OrderCancelledEvent.class);
        reservations.release(event.orderId(), event.storeId());
    }
}
