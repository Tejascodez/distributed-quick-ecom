package com.ims.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    List<Address> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Address> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Address a SET a.isDefault = false WHERE a.userId = :userId AND a.isDefault = true")
    void clearDefault(UUID userId);
}
