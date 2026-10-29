package com.ims.darkstore;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
public class Product {

    @Id
    private UUID id;

    private String sku;

    private String name;

    private String brand;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "base_price")
    private BigDecimal basePrice;

    private String unit;

    protected Product() {
    }

    public UUID getId() { return id; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getBrand() { return brand; }
    public UUID getCategoryId() { return categoryId; }
    public BigDecimal getBasePrice() { return basePrice; }
    public String getUnit() { return unit; }
}
