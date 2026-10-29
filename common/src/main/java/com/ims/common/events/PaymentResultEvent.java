package com.ims.common.events;

import java.util.UUID;

/** Published on payment-success / payment-failed by the payment webhook handler. */
public record PaymentResultEvent(UUID orderId, String gatewayReference, boolean success) {
}
