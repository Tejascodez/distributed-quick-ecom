package com.ims.order;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.ims.common.security.Caller;
import com.ims.common.security.Roles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    record LineRequest(@NotNull UUID productId, @Positive int quantity) {
    }

    record CheckoutRequest(@NotNull UUID storeId, @NotNull UUID deliveryAddressId,
                           @NotEmpty List<@Valid LineRequest> items) {
    }

    record StatusRequest(@NotNull OrderStatus status) {
    }

    record ItemView(UUID productId, String productName, BigDecimal unitPrice, int quantity, BigDecimal totalPrice) {
    }

    record OrderView(UUID id, String orderNumber, UUID storeId, String orderStatus, String paymentStatus,
                     BigDecimal totalItemAmount, BigDecimal deliveryFee, BigDecimal surgeFee,
                     BigDecimal payableAmount, List<ItemView> items) {

        static OrderView of(Order o) {
            return new OrderView(o.getId(), o.getOrderNumber(), o.getStoreId(), o.getOrderStatus().name(),
                    o.getPaymentStatus().name(), o.getTotalItemAmount(), o.getDeliveryFee(), o.getSurgeFee(),
                    o.getPayableAmount(), o.getItems().stream().map(i -> new ItemView(i.getProductId(),
                            i.getProductName(), i.getUnitPrice(), i.getQuantity(), i.getTotalPrice())).toList());
        }
    }

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final OrderService orderService;
    private final OrderRepository orders;
    private final StringRedisTemplate redis;

    OrderController(OrderService orderService, OrderRepository orders, StringRedisTemplate redis) {
        this.orderService = orderService;
        this.orders = orders;
        this.redis = redis;
    }

    /**
     * Checkout. The client-generated Idempotency-Key makes retries over flaky mobile networks safe:
     * Redis SETNX rejects concurrent duplicates fast, the UNIQUE column is the durable guarantee.
     */
    @PostMapping
    ResponseEntity<OrderView> checkout(Caller caller,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody CheckoutRequest req) {
        UUID userId = caller.userId();
        String scopedKey = userId + ":" + idempotencyKey;
        Boolean first = redis.opsForValue().setIfAbsent("idem:" + scopedKey, "1", IDEMPOTENCY_TTL);
        if (!Boolean.TRUE.equals(first)) {
            return orders.findByIdempotencyKey(scopedKey)
                    .map(o -> ResponseEntity.ok(OrderView.of(o)))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Request already in progress"));
        }
        try {
            var lines = req.items().stream().map(l -> new OrderService.CheckoutLine(l.productId(), l.quantity())).toList();
            Order order = orderService.create(userId, req.storeId(), req.deliveryAddressId(), lines, scopedKey);
            return ResponseEntity.status(HttpStatus.CREATED).body(OrderView.of(order));
        } catch (RuntimeException e) {
            redis.delete("idem:" + scopedKey); // allow the client to retry a failed attempt
            throw e;
        }
    }

    @GetMapping("/{id}")
    OrderView get(Caller caller, @PathVariable UUID id) {
        Order order = orders.findWithItemsById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        caller.requireSelfOrRole(order.getUserId(), Roles.ADMIN, Roles.STORE_OPS);
        return OrderView.of(order);
    }

    @PatchMapping("/{id}/status")
    OrderView advance(Caller caller, @PathVariable UUID id, @Valid @RequestBody StatusRequest req) {
        caller.requireAnyRole(Roles.ADMIN, Roles.STORE_OPS);
        orderService.advance(id, req.status());
        return get(caller, id);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<String> illegalTransition(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<String> duplicate(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body("Duplicate request");
    }
}
