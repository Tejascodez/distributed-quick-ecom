package com.ims.order;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

import com.ims.common.events.Topics;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;

class OrderSagaTest extends IntegrationTestBase {

    static final BigDecimal PAYABLE = new BigDecimal("200.00");

    @Test
    void checkoutPricesFromTheCatalogAndPublishesOrderCreated() {
        Order order = newOrder();

        assertThat(order.getPayableAmount()).isEqualByComparingTo(PAYABLE);
        assertThat(order.getOrderNumber()).startsWith("QC");
        assertThat(published(order, Topics.ORDER_CREATED)).hasSize(1);
    }

    @Test
    void orderNumbersNeverRepeat() {
        var numbers = IntStream.range(0, 50).mapToObj(i -> newOrder().getOrderNumber()).toList();

        assertThat(numbers).doesNotHaveDuplicates();
    }

    @Test
    void reservedThenPaidBecomesPaid() {
        Order order = newOrder();

        orderService.onInventoryReserved(order.getId());
        orderService.onPaymentSucceeded(order.getId(), "pay_1" + run, PAYABLE);

        Order paid = reload(order);
        assertThat(paid.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.getPaymentReference()).isEqualTo("pay_1" + run);
        assertThat(published(order, Topics.ORDER_PAID)).hasSize(1);
    }

    @Test
    void paymentBeforeInventoryWaitsThenBecomesPaid() {
        Order order = newOrder();

        orderService.onPaymentSucceeded(order.getId(), "pay_1" + run, PAYABLE);
        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(reload(order).getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);

        orderService.onInventoryReserved(order.getId());

        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(published(order, Topics.ORDER_PAID)).hasSize(1);
    }

    @Test
    void failedPaymentWhilePendingCancelsAndReleasesStock() {
        Order order = newOrder();
        orderService.onInventoryReserved(order.getId());

        orderService.onPaymentFailed(order.getId(), "pay_1" + run);

        Order cancelled = reload(order);
        assertThat(cancelled.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(published(order, Topics.ORDER_CANCELLED)).hasSize(1);
        assertThat(published(order, Topics.PAYMENT_REFUND_REQUESTED)).isEmpty();
    }

    @Test
    void lateFailedAttemptDoesNotCancelAPaidOrder() {
        Order order = newOrder();
        orderService.onInventoryReserved(order.getId());
        orderService.onPaymentSucceeded(order.getId(), "pay_retry" + run, PAYABLE);

        orderService.onPaymentFailed(order.getId(), "pay_first_attempt" + run);

        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(published(order, Topics.ORDER_CANCELLED)).isEmpty();
    }

    @Test
    void wrongAmountIsRefundedAndTheOrderCanStillBePaid() {
        Order order = newOrder();
        orderService.onInventoryReserved(order.getId());

        orderService.onPaymentSucceeded(order.getId(), "pay_short" + run, new BigDecimal("1.00"));

        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        assertThat(reload(order).getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(published(order, Topics.PAYMENT_REFUND_REQUESTED)).singleElement()
                .satisfies(refund -> assertThat(refund).contains("\"gatewayReference\": \"pay_short" + run + "\"",
                        "\"amount\": 1.00", "AMOUNT_MISMATCH"));

        orderService.onPaymentSucceeded(order.getId(), "pay_full" + run, PAYABLE);

        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void missingAmountIsTreatedAsAMismatch() {
        Order order = newOrder();
        orderService.onInventoryReserved(order.getId());

        orderService.onPaymentSucceeded(order.getId(), "pay_1" + run, null);

        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        assertThat(published(order, Topics.PAYMENT_REFUND_REQUESTED)).hasSize(1);
    }

    @Test
    void redeliveredWebhookIsAppliedOnce() {
        Order order = newOrder();
        orderService.onInventoryReserved(order.getId());

        orderService.onPaymentSucceeded(order.getId(), "pay_short" + run, BigDecimal.ONE);
        orderService.onPaymentSucceeded(order.getId(), "pay_short" + run, BigDecimal.ONE);

        assertThat(published(order, Topics.PAYMENT_REFUND_REQUESTED)).hasSize(1);
    }

    @Test
    void secondPaymentForAPaidOrderIsRefunded() {
        Order order = newOrder();
        orderService.onInventoryReserved(order.getId());
        orderService.onPaymentSucceeded(order.getId(), "pay_tab1" + run, PAYABLE);

        orderService.onPaymentSucceeded(order.getId(), "pay_tab2" + run, PAYABLE);

        assertThat(reload(order).getPaymentReference()).isEqualTo("pay_tab1" + run);
        assertThat(published(order, Topics.PAYMENT_REFUND_REQUESTED)).singleElement()
                .satisfies(refund -> assertThat(refund).contains("pay_tab2" + run, "DUPLICATE_PAYMENT"));
        assertThat(published(order, Topics.ORDER_PAID)).hasSize(1);
    }

    @Test
    void paymentForACancelledOrderIsRefunded() {
        Order order = newOrder();
        orderService.onInventoryRejected(order.getId(), "OUT_OF_STOCK");

        orderService.onPaymentSucceeded(order.getId(), "pay_late" + run, PAYABLE);

        assertThat(reload(order).getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(published(order, Topics.PAYMENT_REFUND_REQUESTED)).singleElement()
                .satisfies(refund -> assertThat(refund).contains("pay_late" + run, "ORDER_CANCELLED"));
    }

    @Test
    void inventoryRejectedAfterPaymentRefundsThePayment() {
        Order order = newOrder();
        orderService.onPaymentSucceeded(order.getId(), "pay_1" + run, PAYABLE);

        orderService.onInventoryRejected(order.getId(), "OUT_OF_STOCK");

        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reload(order).getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(published(order, Topics.PAYMENT_REFUND_REQUESTED)).singleElement()
                .satisfies(refund -> assertThat(refund).contains("pay_1" + run, "\"amount\": 200.00", "OUT_OF_STOCK"));
    }

    @Test
    void opsCancellingAPaidOrderRefundsIt() {
        Order order = newOrder();
        orderService.onInventoryReserved(order.getId());
        orderService.onPaymentSucceeded(order.getId(), "pay_1" + run, PAYABLE);

        orderService.advance(order.getId(), OrderStatus.CANCELLED);

        assertThat(published(order, Topics.PAYMENT_REFUND_REQUESTED)).singleElement()
                .satisfies(refund -> assertThat(refund).contains("pay_1" + run, "CANCELLED_BY_OPS"));
        assertThat(published(order, Topics.ORDER_CANCELLED)).hasSize(1);
    }

    /** Both replies race on one order; optimistic locking plus Kafka's retry must still pay it exactly once. */
    @RepeatedTest(10)
    void concurrentInventoryAndPaymentRepliesPayOnce() {
        Order order = newOrder();
        var start = new CountDownLatch(1);

        var inventory = CompletableFuture.runAsync(() -> retryOnConflict(start,
                () -> orderService.onInventoryReserved(order.getId())));
        var payment = CompletableFuture.runAsync(() -> retryOnConflict(start,
                () -> orderService.onPaymentSucceeded(order.getId(), "pay_1" + run, PAYABLE)));
        start.countDown();
        CompletableFuture.allOf(inventory, payment).join();

        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(published(order, Topics.ORDER_PAID)).hasSize(1);
    }

    /** What the Kafka error handler does for a listener: run again after an optimistic-lock conflict. */
    private static void retryOnConflict(CountDownLatch start, Runnable handler) {
        try {
            start.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        for (int attempt = 0; ; attempt++) {
            try {
                handler.run();
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt == 5) {
                    throw e;
                }
            }
        }
    }
}
