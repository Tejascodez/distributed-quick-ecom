package com.ims.notification;

import com.ims.common.events.OrderPaidEvent;
import com.ims.common.events.OrderStatusChangedEvent;
import com.ims.common.events.RiderAssignedEvent;
import com.ims.common.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class OrderEventsListener {

    private final SimpMessagingTemplate ws;
    private final PushSender push;
    private final OrderOwners owners;
    private final JsonMapper json;

    OrderEventsListener(SimpMessagingTemplate ws, PushSender push, OrderOwners owners, JsonMapper json) {
        this.ws = ws;
        this.push = push;
        this.owners = owners;
        this.json = json;
    }

    @KafkaListener(topics = Topics.ORDER_STATUS_CHANGED)
    void onStatusChanged(String payload) {
        var event = json.readValue(payload, OrderStatusChangedEvent.class);
        owners.remember(event.orderId(), event.userId());
        ws.convertAndSend("/topic/orders/" + event.orderId(), event);
        push.send(event.userId(), "Order update", "Your order is now " + event.status());
    }

    /** Wakes the picker UI at the fulfilling dark store. */
    @KafkaListener(topics = Topics.ORDER_PAID)
    void onOrderPaid(String payload) {
        var event = json.readValue(payload, OrderPaidEvent.class);
        owners.remember(event.orderId(), event.userId());
        ws.convertAndSend("/topic/stores/" + event.storeId() + "/picking", event);
    }

    @KafkaListener(topics = Topics.RIDER_ASSIGNED)
    void onRiderAssigned(String payload) {
        var event = json.readValue(payload, RiderAssignedEvent.class);
        ws.convertAndSend("/topic/orders/" + event.orderId(), event);
    }
}
