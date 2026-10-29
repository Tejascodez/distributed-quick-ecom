package com.ims.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_addresses")
public class Address {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    private String label;

    private String line1;

    private String line2;

    private String city;

    private String pincode;

    private double lat;

    private double lng;

    @Column(name = "is_default")
    private boolean isDefault;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Address() {
    }

    Address(UUID userId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
    }

    void update(String label, String line1, String line2, String city, String pincode, double lat, double lng) {
        this.label = label;
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.pincode = pincode;
        this.lat = lat;
        this.lng = lng;
    }

    void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getLabel() { return label; }
    public String getLine1() { return line1; }
    public String getLine2() { return line2; }
    public String getCity() { return city; }
    public String getPincode() { return pincode; }
    public double getLat() { return lat; }
    public double getLng() { return lng; }
    public boolean isDefault() { return isDefault; }
}
