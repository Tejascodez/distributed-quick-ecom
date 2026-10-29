package com.ims.order;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Cancels checkouts that were never paid; order-cancelled makes inventory release the hold. */
@Component
class CheckoutTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(CheckoutTimeoutJob.class);

    private final OrderRepository orders;
    private final OrderService orderService;
    private final TransactionTemplate tx;
    private final Duration timeout;

    CheckoutTimeoutJob(OrderRepository orders, OrderService orderService, TransactionTemplate tx,
                       @Value("${ims.checkout-timeout}") Duration timeout) {
        this.orders = orders;
        this.orderService = orderService;
        this.tx = tx;
        this.timeout = timeout;
    }

    @Scheduled(fixedDelayString = "PT30S")
    void cancelStale() {
        var stale = orders.findTop100ByOrderStatusInAndCreatedAtBefore(
                List.of(OrderStatus.CREATED, OrderStatus.INVENTORY_RESERVED), Instant.now().minus(timeout));
        for (Order order : stale) {
            // One order failing (e.g. a lock conflict with a saga reply) must not stop the rest of the batch.
            try {
                tx.executeWithoutResult(s -> orderService.cancel(orderService.load(order.getId()), "CHECKOUT_TIMEOUT"));
            } catch (RuntimeException e) {
                log.warn("Could not time out order {}; retrying next run", order.getId(), e);
            }
        }
    }
}
