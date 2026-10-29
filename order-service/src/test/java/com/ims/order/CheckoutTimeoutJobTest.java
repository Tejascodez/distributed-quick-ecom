package com.ims.order;

import com.ims.common.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

class CheckoutTimeoutJobTest extends IntegrationTestBase {

    @Autowired
    CheckoutTimeoutJob job;

    @Test
    void cancelsUnpaidCheckoutsPastTheTimeout() {
        Order stale = newOrder();
        Order fresh = newOrder();
        backdate(stale, 11);

        job.cancelStale();

        assertThat(reload(stale).getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(published(stale, Topics.ORDER_CANCELLED)).singleElement()
                .satisfies(event -> assertThat(event).contains("CHECKOUT_TIMEOUT"));
        assertThat(reload(fresh).getOrderStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void leavesPaidOrdersAlone() {
        Order order = newOrder();
        orderService.onInventoryReserved(order.getId());
        orderService.onPaymentSucceeded(order.getId(), "pay_1" + run, OrderSagaTest.PAYABLE);
        backdate(order, 11);

        job.cancelStale();

        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void oneFailingOrderDoesNotBlockTheRest() {
        Order broken = newOrder();
        Order stale = newOrder();
        backdate(broken, 12); // oldest, so it comes first in the batch
        backdate(stale, 11);
        doThrow(new IllegalStateException("boom")).when(orderService).load(broken.getId());

        job.cancelStale();

        assertThat(reload(stale).getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reload(broken).getOrderStatus()).isEqualTo(OrderStatus.CREATED);
    }

    private void backdate(Order order, int minutes) {
        jdbc.update("UPDATE orders SET created_at = created_at - make_interval(mins => ?) WHERE id = ?",
                minutes, order.getId());
    }
}
