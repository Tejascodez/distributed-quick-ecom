package com.ims.darkstore;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "dark_stores")
public class DarkStore {

    public enum Status { ACTIVE, INACTIVE, OVERLOADED }

    @Id
    private UUID id;

    private String name;

    private String address;

    @Column(columnDefinition = "geometry(Point,4326)")
    private Point location;

    @Column(name = "h3_cell")
    private long h3Cell;

    @Column(name = "serviceable_radius_meters")
    private int serviceableRadiusMeters;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected DarkStore() {
    }

    public DarkStore(String name, String address, Point location, long h3Cell, int serviceableRadiusMeters) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.address = address;
        this.location = location;
        this.h3Cell = h3Cell;
        this.serviceableRadiusMeters = serviceableRadiusMeters;
        this.status = Status.ACTIVE;
        this.updatedAt = Instant.now();
    }

    /** OVERLOADED or INACTIVE stores stop taking new orders: lookups and delivers-to only match ACTIVE ones. */
    public void setStatus(Status status) {
        this.status = status;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public Point getLocation() { return location; }
    public long getH3Cell() { return h3Cell; }
    public int getServiceableRadiusMeters() { return serviceableRadiusMeters; }
    public Status getStatus() { return status; }
    public Instant getUpdatedAt() { return updatedAt; }
}
