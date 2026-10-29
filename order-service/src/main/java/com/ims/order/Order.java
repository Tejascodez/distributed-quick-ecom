package com.ims.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(name = "order_number")
    private String orderNumber;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "store_id")
    private UUID storeId;

    @Column(name = "delivery_address_id")
    private UUID deliveryAddressId;

    @Column(name = "total_item_amount")
    private BigDecimal totalItemAmount = BigDecimal.ZERO;

    @Column(name = "delivery_fee")
    private BigDecimal deliveryFee = BigDecimal.ZERO;

    @Column(name = "surge_fee")
    private BigDecimal surgeFee = BigDecimal.ZERO;

    @Column(name = "payable_amount")
    private BigDecimal payableAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status")
    private OrderStatus orderStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status")
    private PaymentStatus paymentStatus;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    /** Saga replies for one order can arrive concurrently; optimistic locking serialises them. */
    @Version
    private long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    Order(String orderNumber, UUID userId, UUID storeId, UUID deliveryAddressId, String idempotencyKey) {
        this.id = UUID.randomUUID();
        this.orderNumber = orderNumber;
        this.userId = userId;
        this.storeId = storeId;
        this.deliveryAddressId = deliveryAddressId;
        this.idempotencyKey = idempotencyKey;
        this.orderStatus = OrderStatus.CREATED;
        this.paymentStatus = PaymentStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    void addItem(UUID productId, String name, BigDecimal unitPrice, int quantity) {
        OrderItem item = new OrderItem(this, productId, name, unitPrice, quantity);
        items.add(item);
        totalItemAmount = totalItemAmount.add(item.getTotalPrice());
    }

    void applyFees(BigDecimal deliveryFee, BigDecimal surgeFee) {
        this.deliveryFee = deliveryFee;
        this.surgeFee = surgeFee;
        this.payableAmount = totalItemAmount.add(deliveryFee).add(surgeFee);
    }

    void transitionTo(OrderStatus next) {
        if (!orderStatus.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal transition " + orderStatus + " -> " + next + " for order " + id);
        }
        orderStatus = next;
    }

    void setPaymentStatus(PaymentStatus paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public UUID getUserId() { return userId; }
    public UUID getStoreId() { return storeId; }
    public BigDecimal getTotalItemAmount() { return totalItemAmount; }
    public BigDecimal getDeliveryFee() { return deliveryFee; }
    public BigDecimal getSurgeFee() { return surgeFee; }
    public BigDecimal getPayableAmount() { return payableAmount; }
    public OrderStatus getOrderStatus() { return orderStatus; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public List<OrderItem> getItems() { return items; }
}
