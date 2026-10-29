package com.ims.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ims.common.events.OrderCancelledEvent;
import com.ims.common.events.OrderCreatedEvent;
import com.ims.common.events.OrderLine;
import com.ims.common.events.OrderPaidEvent;
import com.ims.common.events.OrderStatusChangedEvent;
import com.ims.common.events.Topics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns order state and orchestrates the checkout saga (inventory + payment -> PAID). */
@Service
public class OrderService {

    public record CheckoutLine(UUID productId, int quantity) {
    }

    private static final BigDecimal FREE_DELIVERY_THRESHOLD = new BigDecimal("199.00");
    private static final BigDecimal DELIVERY_FEE = new BigDecimal("25.00");

    private final OrderRepository orders;
    private final CatalogClient catalog;
    private final AddressClient addresses;
    private final Outbox outbox;

    OrderService(OrderRepository orders, CatalogClient catalog, AddressClient addresses, Outbox outbox) {
        this.orders = orders;
        this.catalog = catalog;
        this.addresses = addresses;
        this.outbox = outbox;
    }

    @Transactional
    public Order create(UUID userId, UUID storeId, UUID addressId, List<CheckoutLine> lines, String idempotencyKey) {
        var address = addresses.find(userId, addressId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown delivery address " + addressId));
        // Stock and prices are per store, so the cart's store must deliver to this address.
        if (!catalog.deliversTo(storeId, address.lat(), address.lng())) {
            throw new IllegalArgumentException("Store " + storeId + " does not deliver to this address");
        }
        Map<UUID, CatalogClient.ProductPrice> prices = catalog.prices(lines.stream().map(CheckoutLine::productId).toList())
                .stream().collect(Collectors.toMap(CatalogClient.ProductPrice::id, Function.identity()));

        Order order = new Order(newOrderNumber(), userId, storeId, addressId, idempotencyKey);
        for (CheckoutLine line : lines) {
            var price = prices.get(line.productId());
            if (price == null) {
                throw new IllegalArgumentException("Unknown product " + line.productId());
            }
            order.addItem(price.id(), price.name(), price.basePrice(), line.quantity());
        }
        boolean freeDelivery = order.getTotalItemAmount().compareTo(FREE_DELIVERY_THRESHOLD) >= 0;
        order.applyFees(freeDelivery ? BigDecimal.ZERO : DELIVERY_FEE, BigDecimal.ZERO);
        orders.save(order);

        outbox.publish(Topics.ORDER_CREATED, order.getId(), new OrderCreatedEvent(order.getId(), userId, storeId,
                lines(order), order.getPayableAmount()));
        return order;
    }

    @Transactional
    public void onInventoryReserved(UUID orderId) {
        Order order = load(orderId);
        if (order.getOrderStatus() != OrderStatus.CREATED) {
            return; // duplicate delivery or already cancelled (release is handled by order-cancelled)
        }
        changeStatus(order, OrderStatus.INVENTORY_RESERVED);
        if (order.getPaymentStatus() == PaymentStatus.SUCCESS) {
            markPaid(order);
        }
    }

    @Transactional
    public void onInventoryRejected(UUID orderId, String reason) {
        cancel(load(orderId), reason);
    }

    @Transactional
    public void onPaymentSucceeded(UUID orderId) {
        Order order = load(orderId);
        if (order.getPaymentStatus() == PaymentStatus.SUCCESS) {
            return;
        }
        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            order.setPaymentStatus(PaymentStatus.REFUNDED); // late payment on a dead order; trigger refund
            return;
        }
        order.setPaymentStatus(PaymentStatus.SUCCESS);
        if (order.getOrderStatus() == OrderStatus.INVENTORY_RESERVED) {
            markPaid(order);
        }
    }

    @Transactional
    public void onPaymentFailed(UUID orderId) {
        Order order = load(orderId);
        if (order.getPaymentStatus() == PaymentStatus.PENDING) {
            order.setPaymentStatus(PaymentStatus.FAILED);
        }
        cancel(order, "PAYMENT_FAILED");
    }

    /** Fulfilment progress from the dark store dashboard / rider app. */
    @Transactional
    public void advance(UUID orderId, OrderStatus next) {
        Order order = load(orderId);
        if (next == OrderStatus.CANCELLED) {
            cancel(order, "CANCELLED_BY_OPS");
        } else {
            changeStatus(order, next);
        }
    }

    @Transactional
    public void cancel(Order order, String reason) {
        if (!order.getOrderStatus().canTransitionTo(OrderStatus.CANCELLED)) {
            return;
        }
        changeStatus(order, OrderStatus.CANCELLED);
        if (order.getPaymentStatus() == PaymentStatus.SUCCESS) {
            order.setPaymentStatus(PaymentStatus.REFUNDED);
        }
        outbox.publish(Topics.ORDER_CANCELLED, order.getId(),
                new OrderCancelledEvent(order.getId(), order.getStoreId(), lines(order), reason));
    }

    private void markPaid(Order order) {
        changeStatus(order, OrderStatus.PAID);
        outbox.publish(Topics.ORDER_PAID, order.getId(),
                new OrderPaidEvent(order.getId(), order.getUserId(), order.getStoreId()));
    }

    private void changeStatus(Order order, OrderStatus next) {
        order.transitionTo(next);
        outbox.publish(Topics.ORDER_STATUS_CHANGED, order.getId(),
                new OrderStatusChangedEvent(order.getId(), order.getUserId(), next.name()));
    }

    Order load(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> new IllegalArgumentException("Unknown order " + orderId));
    }

    private static List<OrderLine> lines(Order order) {
        return order.getItems().stream().map(i -> new OrderLine(i.getProductId(), i.getQuantity())).toList();
    }

    private static String newOrderNumber() {
        return "QC" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
