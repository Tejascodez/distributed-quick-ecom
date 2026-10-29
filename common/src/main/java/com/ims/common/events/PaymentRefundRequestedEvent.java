package com.ims.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/** Published on payment-refund-requested: money was captured for an order that won't be fulfilled with it. */
public record PaymentRefundRequestedEvent(UUID orderId, UUID userId, String gatewayReference, BigDecimal amount,
                                          String reason) {
}
