package com.ims.delivery;

import java.util.Map;
import java.util.UUID;

import com.ims.common.security.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/** Rider app endpoints over real HTTP, sent the way Envoy forwards them. */
class RiderApiTest extends IntegrationTestBase {

    @Value("${local.server.port}")
    int port;

    RestClient http;
    final UUID order = UUID.randomUUID();

    @BeforeEach
    void setUpClient() {
        http = RestClient.create("http://localhost:" + port);
    }

    @Test
    void deliveryStepsHappenOnceAndInOrder() {
        UUID rider = onlineRider(0.5);
        dispatch.tryAssign(order, store);

        assertThat(post(rider, "/assignments/" + order + "/delivered", null)).isEqualTo(409); // not picked up
        assertThat(post(rider, "/assignments/" + order + "/picked-up", null)).isEqualTo(200);
        assertThat(post(rider, "/assignments/" + order + "/picked-up", null)).isEqualTo(200); // app retry
        assertThat(partnerStatus(rider)).isEqualTo("DELIVERING");
        assertThat(post(rider, "/assignments/" + order + "/delivered", null)).isEqualTo(200);
        assertThat(post(rider, "/assignments/" + order + "/delivered", null)).isEqualTo(200); // app retry
        assertThat(post(rider, "/assignments/" + order + "/picked-up", null)).isEqualTo(409);

        assertThat(partnerStatus(rider)).isEqualTo("IDLE");
        assertThat(marker(rider)).isNull();
        ping(rider, 0.5);
        assertThat(idle(rider)).isTrue();
    }

    @Test
    void cancelledAssignmentCannotBePickedUp() {
        UUID rider = onlineRider(0.5);
        dispatch.tryAssign(order, store);
        dispatch.cancel(order, store);

        assertThat(post(rider, "/assignments/" + order + "/picked-up", null)).isEqualTo(409);
    }

    @Test
    void anotherRiderCannotCompleteTheDelivery() {
        onlineRider(0.5);
        dispatch.tryAssign(order, store);

        assertThat(post(UUID.randomUUID(), "/assignments/" + order + "/picked-up", null)).isEqualTo(403);
    }

    @Test
    void pingWithAnImpossibleLatitudeIsRejected() {
        UUID rider = onlineRider(0.5);

        assertThat(post(rider, "/partners/" + rider + "/location", Map.of("lat", 91.0, "lng", 10.0))).isEqualTo(400);
    }

    @Test
    void pingFromABusyRiderIsRelayedNotPooled() {
        UUID rider = onlineRider(0.5);
        dispatch.tryAssign(order, store);

        assertThat(post(rider, "/partners/" + rider + "/location", Map.of("lat", storeLat, "lng", storeLng)))
                .isEqualTo(200);

        assertThat(idle(rider)).isFalse();
    }

    @Test
    void goingOfflineLeavesThePool() {
        UUID rider = onlineRider(0.5);

        assertThat(post(rider, "/partners/" + rider + "/offline", null)).isEqualTo(200);
        ping(rider, 0.5);

        assertThat(idle(rider)).isFalse();
    }

    @Test
    void registeringTheSamePhoneTwiceIsAConflict() {
        String phone = randomPhone();
        var first = Map.of("userId", UUID.randomUUID(), "fullName", "A", "phone", phone, "vehicleType", "BIKE");
        var second = Map.of("userId", UUID.randomUUID(), "fullName", "B", "phone", phone, "vehicleType", "BIKE");

        assertThat(postAsAdmin("/partners", first)).isEqualTo(201);
        assertThat(postAsAdmin("/partners", second)).isEqualTo(409);
    }

    private int post(UUID rider, String path, Object body) {
        return send(rider, "RIDER", path, body);
    }

    private int postAsAdmin(String path, Object body) {
        return send(UUID.randomUUID(), "ADMIN", path, body);
    }

    private int send(UUID user, String role, String path, Object body) {
        var spec = http.post().uri("/api/v1/delivery" + path)
                .header(Caller.USER_ID_HEADER, user.toString()).header(Caller.ROLE_HEADER, role);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity().getStatusCode().value();
    }
}
