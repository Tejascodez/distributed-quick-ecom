package com.ims.order;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    private int quantity;

    @Column(name = "total_price")
    private BigDecimal totalPrice;

    protected OrderItem() {
    }

    OrderItem(Order order, UUID productId, String productName, BigDecimal unitPrice, int quantity) {
        this.order = order;
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.totalPrice = unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public UUID getProductId() { return productId; }
    public String getProductName() { return productName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public BigDecimal getTotalPrice() { return totalPrice; }
}
