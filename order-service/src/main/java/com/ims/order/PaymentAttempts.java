package com.ims.order;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ledger of payment results by gateway reference; makes webhook redelivery a no-op. */
@Component
class PaymentAttempts {

    private final JdbcTemplate jdbc;

    PaymentAttempts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the result in the caller's transaction; false if this reference was already seen. */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean recordFirst(String gatewayReference, UUID orderId, boolean success, BigDecimal amount) {
        return jdbc.update("""
                INSERT INTO payment_attempts (gateway_reference, order_id, success, amount) VALUES (?, ?, ?, ?)
                ON CONFLICT (gateway_reference) DO NOTHING
                """, gatewayReference, orderId, success, amount) == 1;
    }
}
