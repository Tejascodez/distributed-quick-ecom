package com.ims.order;

import java.math.BigDecimal;
import java.util.UUID;

import com.ims.common.events.PaymentResultEvent;
import com.ims.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Payment gateway callback. The gateway is not a logged-in user, so Envoy lets this route through
 * without a JWT; the HMAC signature is what authenticates it. The result goes through the outbox
 * so the saga consumes it like any other reply.
 */
@RestController
class PaymentWebhookController {

    /** amount is what the gateway captured; the saga refunds it unless it matches the order's payable amount. */
    record WebhookRequest(UUID orderId, String gatewayReference, boolean success, BigDecimal amount) {
    }

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final Outbox outbox;
    private final WebhookSignatureVerifier verifier;
    private final JsonMapper json;

    PaymentWebhookController(Outbox outbox, WebhookSignatureVerifier verifier, JsonMapper json) {
        this.outbox = outbox;
        this.verifier = verifier;
        this.json = json;
    }

    /** Takes the raw bytes: the signature covers the body exactly as sent, not a re-serialised copy. */
    @PostMapping("/api/v1/payments/webhook")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    void webhook(@RequestHeader(name = "X-Webhook-Timestamp", required = false) String timestamp,
                 @RequestHeader(name = "X-Webhook-Signature", required = false) String signature,
                 @RequestBody byte[] body) {
        if (!verifier.isValid(timestamp, signature, body)) {
            log.warn("Rejected payment webhook with invalid or stale signature");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
        WebhookRequest req;
        try {
            req = json.readValue(body, WebhookRequest.class);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed webhook body");
        }
        if (req.orderId() == null || req.gatewayReference() == null || req.gatewayReference().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderId and gatewayReference are required");
        }
        if (req.success() && (req.amount() == null || req.amount().signum() <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A successful payment needs a positive amount");
        }
        outbox.publish(req.success() ? Topics.PAYMENT_SUCCESS : Topics.PAYMENT_FAILED, req.orderId(),
                new PaymentResultEvent(req.orderId(), req.gatewayReference(), req.success(), req.amount()));
    }
}
