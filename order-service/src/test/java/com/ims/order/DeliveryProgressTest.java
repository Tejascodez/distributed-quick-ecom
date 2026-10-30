package com.ims.order;

import java.util.List;

import com.ims.common.events.Topics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rider pickup and hand-over driving the order's status. */
class DeliveryProgressTest extends IntegrationTestBase {

    @Test
    void pickupOfAPaidOrderSendsItOutEvenIfNeverPacked() {
        Order order = paidOrder();

        orderService.onPickedUp(order.getId());

        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
        assertThat(statuses(order)).endsWith("PAID", "OUT_FOR_DELIVERY");
    }

    @Test
    void pickupOfAPackedOrderSendsItOut() {
        Order order = paidOrder();
        orderService.advance(order.getId(), OrderStatus.PICKING);
        orderService.advance(order.getId(), OrderStatus.PACKED);

        orderService.onPickedUp(order.getId());

        assertThat(statuses(order)).endsWith("PICKING", "PACKED", "OUT_FOR_DELIVERY");
    }

    @Test
    void handOverDeliversTheOrder() {
        Order order = paidOrder();
        orderService.onPickedUp(order.getId());

        orderService.onDelivered(order.getId());

        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(statuses(order)).endsWith("OUT_FOR_DELIVERY", "DELIVERED");
    }

    @Test
    void handOverBeforeThePickupEventStillPassesThroughOutForDelivery() {
        Order order = paidOrder();

        orderService.onDelivered(order.getId());

        assertThat(statuses(order)).endsWith("PAID", "OUT_FOR_DELIVERY", "DELIVERED");
    }

    @Test
    void redeliveredAndLateStepsChangeNothing() {
        Order order = paidOrder();
        orderService.onPickedUp(order.getId());
        orderService.onDelivered(order.getId());
        int before = statuses(order).size();

        orderService.onPickedUp(order.getId()); // late pickup after hand-over
        orderService.onDelivered(order.getId()); // redelivered

        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(statuses(order)).hasSize(before);
    }

    /** Ops cancelled while the rider was tapping pickup: the refund stands and the order stays cancelled. */
    @Test
    void cancelledOrderStaysCancelled() {
        Order order = paidOrder();
        orderService.advance(order.getId(), OrderStatus.CANCELLED);

        orderService.onPickedUp(order.getId());
        orderService.onDelivered(order.getId());

        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(statuses(order)).endsWith("CANCELLED");
    }

    @Test
    void pickupOfAnUnpaidOrderIsRejected() {
        Order order = newOrder();

        assertThatThrownBy(() -> orderService.onPickedUp(order.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void packedOrderCanNoLongerBeCancelled() {
        Order order = paidOrder();
        orderService.advance(order.getId(), OrderStatus.PICKING);
        orderService.advance(order.getId(), OrderStatus.PACKED);

        orderService.advance(order.getId(), OrderStatus.CANCELLED);

        assertThat(reload(order).getOrderStatus()).isEqualTo(OrderStatus.PACKED);
    }

    private Order paidOrder() {
        Order order = newOrder();
        orderService.onInventoryReserved(order.getId());
        orderService.onPaymentSucceeded(order.getId(), "pay_1" + run, OrderSagaTest.PAYABLE);
        return order;
    }

    /** The statuses announced on order-status-changed, oldest first. */
    private List<String> statuses(Order order) {
        return published(order, Topics.ORDER_STATUS_CHANGED).stream()
                .map(p -> p.replaceAll(".*\"status\"\\s*:\\s*\"([A-Z_]+)\".*", "$1")).toList();
    }
}
