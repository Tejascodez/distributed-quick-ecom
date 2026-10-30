package com.ims.delivery;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.ims.common.security.Caller;
import com.ims.common.security.Roles;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/delivery")
class RiderController {

    /** userId: the rider's user account (role RIDER); it becomes the partner id. */
    record RegisterRequest(@NotNull UUID userId, @NotBlank String fullName, @NotBlank String phone,
                           @Pattern(regexp = "BIKE|EV_SCOOTER|CYCLE") String vehicleType) {
    }

    /** Redis GEO only accepts latitudes within ±85.05112878. */
    record LocationPing(@DecimalMin("-85.05112878") @DecimalMax("85.05112878") double lat,
                        @DecimalMin("-180") @DecimalMax("180") double lng) {
    }

    private record AssignmentState(UUID partnerId, boolean pickedUp, boolean delivered, boolean cancelled) {
    }

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final RiderPool pool;
    private final JsonMapper json;

    RiderController(JdbcTemplate jdbc, StringRedisTemplate redis, RiderPool pool, JsonMapper json) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.pool = pool;
        this.json = json;
    }

    @PostMapping("/partners")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> register(Caller caller, @Valid @RequestBody RegisterRequest req) {
        caller.requireAnyRole(Roles.ADMIN);
        UUID id = req.userId();
        jdbc.update("INSERT INTO delivery_partners (id, full_name, phone, vehicle_type) VALUES (?, ?, ?, ?)",
                id, req.fullName(), req.phone(), req.vehicleType());
        return Map.of("id", id);
    }

    @PostMapping("/partners/{id}/online")
    void online(Caller caller, @PathVariable UUID id, @Valid @RequestBody LocationPing ping) {
        caller.requireSelfOrRole(id, Roles.ADMIN);
        jdbc.update("UPDATE delivery_partners SET is_online = TRUE WHERE id = ?", id);
        pool.online(id);
        location(caller, id, ping);
    }

    @PostMapping("/partners/{id}/offline")
    void offline(Caller caller, @PathVariable UUID id) {
        caller.requireSelfOrRole(id, Roles.ADMIN);
        jdbc.update("UPDATE delivery_partners SET is_online = FALSE WHERE id = ?", id);
        pool.offline(id);
    }

    /** High-frequency ping from the rider app (every few seconds). */
    @PostMapping("/partners/{id}/location")
    void location(Caller caller, @PathVariable UUID id, @Valid @RequestBody LocationPing ping) {
        caller.requireSelfOrRole(id, Roles.ADMIN);
        UUID activeOrder = pool.ping(id, ping.lat(), ping.lng());
        if (activeOrder != null) {
            // Fan out to the customer's live map via notification-service.
            redis.convertAndSend(RiderKeys.locationChannel(activeOrder),
                    json.writeValueAsString(Map.of("partnerId", id, "lat", ping.lat(), "lng", ping.lng())));
        }
    }

    /** Idempotent: the rider app retries over flaky networks, so repeating a step that already happened is OK. */
    @PostMapping("/assignments/{orderId}/picked-up")
    @Transactional
    void pickedUp(Caller caller, @PathVariable UUID orderId) {
        AssignmentState state = requireAssignedRider(caller, orderId);
        if (state.cancelled() || state.delivered()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    state.cancelled() ? "Order was cancelled" : "Order was already delivered");
        }
        if (state.pickedUp()) {
            return;
        }
        jdbc.update("UPDATE delivery_assignments SET picked_up_at = now() WHERE order_id = ?", orderId);
        jdbc.update("UPDATE delivery_partners SET current_status = 'DELIVERING' WHERE id = ?", state.partnerId());
    }

    @PostMapping("/assignments/{orderId}/delivered")
    @Transactional
    void delivered(Caller caller, @PathVariable UUID orderId) {
        AssignmentState state = requireAssignedRider(caller, orderId);
        if (state.delivered()) {
            return;
        }
        if (state.cancelled() || !state.pickedUp()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    state.cancelled() ? "Order was cancelled" : "Order hasn't been picked up");
        }
        jdbc.update("UPDATE delivery_assignments SET delivered_at = now() WHERE order_id = ?", orderId);
        jdbc.update("UPDATE delivery_partners SET current_status = 'IDLE' WHERE id = ?", state.partnerId());
        // After commit, so a rollback can't leave a free rider whose delivery is still open in Postgres.
        // The rider rejoins the idle GEO pool on their next location ping.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pool.release(state.partnerId(), orderId);
            }
        });
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<String> duplicate(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body("Rider already registered (same user or phone)");
    }

    /** Locks the assignment row, so two taps from the rider app are applied one after the other. */
    private AssignmentState requireAssignedRider(Caller caller, UUID orderId) {
        AssignmentState state = jdbc.query("""
                        SELECT partner_id, picked_up_at IS NOT NULL, delivered_at IS NOT NULL, cancelled_at IS NOT NULL
                        FROM delivery_assignments WHERE order_id = ? FOR UPDATE
                        """, (rs, i) -> new AssignmentState(rs.getObject(1, UUID.class), rs.getBoolean(2),
                        rs.getBoolean(3), rs.getBoolean(4)), orderId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No assignment for order"));
        caller.requireSelfOrRole(state.partnerId(), Roles.ADMIN, Roles.STORE_OPS);
        return state;
    }
}
