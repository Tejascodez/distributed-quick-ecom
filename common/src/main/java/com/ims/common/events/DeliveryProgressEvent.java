package com.ims.common.events;

import java.util.UUID;

/** A rider picked the order up at the store, or handed it to the customer. Keyed by order id. */
public record DeliveryProgressEvent(UUID orderId, UUID partnerId, Stage stage) {

    public enum Stage { PICKED_UP, DELIVERED }
}
