package com.ims.delivery;

import com.ims.common.events.OrderCancelledEvent;
import com.ims.common.events.OrderPaidEvent;
import com.ims.common.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class DeliveryEventsListener {

    private final DispatchService dispatch;
    private final JsonMapper json;

    DeliveryEventsListener(DispatchService dispatch, JsonMapper json) {
        this.dispatch = dispatch;
        this.json = json;
    }

    @KafkaListener(topics = Topics.ORDER_PAID)
    void onOrderPaid(String payload) {
        var event = json.readValue(payload, OrderPaidEvent.class);
        dispatch.enqueue(event.orderId(), event.storeId());
    }

    @KafkaListener(topics = Topics.ORDER_CANCELLED)
    void onOrderCancelled(String payload) {
        var event = json.readValue(payload, OrderCancelledEvent.class);
        dispatch.cancel(event.orderId(), event.storeId());
    }
}
