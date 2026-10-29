package com.ims.delivery;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.ims.common.security.Caller;
import com.ims.common.security.Roles;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
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

    record LocationPing(double lat, double lng) {
    }

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    RiderController(JdbcTemplate jdbc, StringRedisTemplate redis, JsonMapper json) {
        this.jdbc = jdbc;
        this.redis = redis;
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
    void online(Caller caller, @PathVariable UUID id, @RequestBody LocationPing ping) {
        caller.requireSelfOrRole(id, Roles.ADMIN);
        jdbc.update("UPDATE delivery_partners SET is_online = TRUE WHERE id = ?", id);
        location(caller, id, ping);
    }

    @PostMapping("/partners/{id}/offline")
    void offline(Caller caller, @PathVariable UUID id) {
        caller.requireSelfOrRole(id, Roles.ADMIN);
        jdbc.update("UPDATE delivery_partners SET is_online = FALSE WHERE id = ?", id);
        redis.opsForZSet().remove(RiderKeys.IDLE, id.toString());
    }

    /** High-frequency ping from the rider app (every few seconds). */
    @PostMapping("/partners/{id}/location")
    void location(Caller caller, @PathVariable UUID id, @RequestBody LocationPing ping) {
        caller.requireSelfOrRole(id, Roles.ADMIN);
        String activeOrder = redis.opsForValue().get(RiderKeys.activeOrder(id));
        if (activeOrder == null) {
            redis.opsForGeo().add(RiderKeys.IDLE, new Point(ping.lng(), ping.lat()), id.toString());
        } else {
            // Fan out to the customer's live map via notification-service.
            redis.convertAndSend(RiderKeys.locationChannel(UUID.fromString(activeOrder)),
                    json.writeValueAsString(Map.of("partnerId", id, "lat", ping.lat(), "lng", ping.lng())));
        }
    }

    @PostMapping("/assignments/{orderId}/picked-up")
    @Transactional
    void pickedUp(Caller caller, @PathVariable UUID orderId) {
        requireAssignedRider(caller, orderId);
        jdbc.update("UPDATE delivery_assignments SET picked_up_at = now() WHERE order_id = ?", orderId);
        jdbc.update("""
                UPDATE delivery_partners SET current_status = 'DELIVERING'
                WHERE id = (SELECT partner_id FROM delivery_assignments WHERE order_id = ?)
                """, orderId);
    }

    @PostMapping("/assignments/{orderId}/delivered")
    @Transactional
    void delivered(Caller caller, @PathVariable UUID orderId) {
        requireAssignedRider(caller, orderId);
        UUID partnerId = jdbc.queryForObject(
                "UPDATE delivery_assignments SET delivered_at = now() WHERE order_id = ? RETURNING partner_id",
                UUID.class, orderId);
        jdbc.update("UPDATE delivery_partners SET current_status = 'IDLE' WHERE id = ?", partnerId);
        // Rider rejoins the idle GEO pool on their next location ping.
        redis.delete(RiderKeys.activeOrder(partnerId));
    }

    private void requireAssignedRider(Caller caller, UUID orderId) {
        UUID partnerId = jdbc.query("SELECT partner_id FROM delivery_assignments WHERE order_id = ?",
                        (rs, i) -> rs.getObject(1, UUID.class), orderId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No assignment for order"));
        caller.requireSelfOrRole(partnerId, Roles.ADMIN, Roles.STORE_OPS);
    }
}
