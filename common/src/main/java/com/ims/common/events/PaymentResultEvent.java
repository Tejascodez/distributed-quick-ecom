package com.ims.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/** Published on payment-success / payment-failed by the payment webhook handler. amount is what the gateway captured. */
public record PaymentResultEvent(UUID orderId, String gatewayReference, boolean success, BigDecimal amount) {
}
