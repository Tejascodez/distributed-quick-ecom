package com.ims.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    List<Order> findTop100ByOrderStatusInAndCreatedAtBefore(List<OrderStatus> statuses, Instant cutoff);
}
