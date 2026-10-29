package com.ims.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(name = "full_name")
    private String fullName;

    private String phone;

    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    private String role;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected User() {
    }

    public User(String fullName, String phone, String email, String passwordHash, String role) {
        this.id = UUID.randomUUID();
        this.fullName = fullName;
        this.phone = phone;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    void setRole(String role) {
        this.role = role;
    }

    void updateProfile(String fullName, String email) {
        if (fullName != null) {
            this.fullName = fullName;
        }
        if (email != null) {
            this.email = email.isBlank() ? null : email;
        }
    }

    public UUID getId() { return id; }
    public String getFullName() { return fullName; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole() { return role; }
}
