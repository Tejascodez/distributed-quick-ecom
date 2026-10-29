package com.ims.order;

import com.ims.common.events.InventoryResultEvent;
import com.ims.common.events.PaymentResultEvent;
import com.ims.common.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Saga replies. OptimisticLockException is retried by Kafka's default error handler. */
@Component
class SagaListener {

    private final OrderService orders;
    private final JsonMapper json;

    SagaListener(OrderService orders, JsonMapper json) {
        this.orders = orders;
        this.json = json;
    }

    @KafkaListener(topics = {Topics.INVENTORY_RESERVED, Topics.INVENTORY_REJECTED})
    void onInventoryResult(String payload) {
        var event = json.readValue(payload, InventoryResultEvent.class);
        if (event.reserved()) {
            orders.onInventoryReserved(event.orderId());
        } else {
            orders.onInventoryRejected(event.orderId(), event.reason());
        }
    }

    @KafkaListener(topics = {Topics.PAYMENT_SUCCESS, Topics.PAYMENT_FAILED})
    void onPaymentResult(String payload) {
        var event = json.readValue(payload, PaymentResultEvent.class);
        if (event.success()) {
            orders.onPaymentSucceeded(event.orderId());
        } else {
            orders.onPaymentFailed(event.orderId());
        }
    }
}
