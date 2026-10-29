package com.ims.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

class UserIntegrationTest extends IntegrationTestBase {

    @Autowired
    AdminBootstrap adminBootstrap;

    private Tokens admin() {
        if (users.findByPhone(ADMIN_PHONE).isEmpty()) {
            register(ADMIN_PHONE);
        }
        adminBootstrap.promote();
        return login(ADMIN_PHONE);
    }

    @Test
    void bootstrapPromotesConfiguredPhoneToAdmin() {
        assertThat(role(admin().access())).isEqualTo("ADMIN");
    }

    @Test
    void meReturnsOwnProfile() {
        String phone = randomPhone();
        Tokens t = register(phone);
        Response r = callAs(t.access(), HttpMethod.GET, "/api/v1/users/me", null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("phone")).isEqualTo(phone);
        assertThat(r.text("role")).isEqualTo("CUSTOMER");
    }

    @Test
    void patchUpdatesOnlyGivenFields() {
        Tokens t = register();
        String email = "u" + System.nanoTime() + "@example.com";

        callAs(t.access(), HttpMethod.PATCH, "/api/v1/users/me", Map.of("email", email));
        Response r = callAs(t.access(), HttpMethod.PATCH, "/api/v1/users/me", Map.of("fullName", "Renamed"));

        assertThat(r.text("fullName")).isEqualTo("Renamed");
        assertThat(r.text("email")).isEqualTo(email);
    }

    @Test
    void emailTakenByAnotherUserIs409() {
        String email = "taken" + System.nanoTime() + "@example.com";
        callAs(register().access(), HttpMethod.PATCH, "/api/v1/users/me", Map.of("email", email));
        assertThat(callAs(register().access(), HttpMethod.PATCH, "/api/v1/users/me", Map.of("email", email)).status())
                .isEqualTo(409);
    }

    @Test
    void invalidProfileInputIs400() {
        Tokens t = register();
        assertThat(callAs(t.access(), HttpMethod.PATCH, "/api/v1/users/me", Map.of("email", "nope")).status()).isEqualTo(400);
        assertThat(callAs(t.access(), HttpMethod.PATCH, "/api/v1/users/me", Map.of("fullName", "")).status()).isEqualTo(400);
    }

    @Test
    void roleCannotBeSetThroughProfile() {
        Tokens t = register();
        Map<String, Object> body = new HashMap<>(Map.of("role", "ADMIN"));
        assertThat(callAs(t.access(), HttpMethod.PATCH, "/api/v1/users/me", body).text("role")).isEqualTo("CUSTOMER");
    }

    @Test
    void customerCannotChangeRoles() {
        Tokens t = register();
        assertThat(callAs(t.access(), HttpMethod.PUT, "/api/v1/users/" + userId(t.access()) + "/role",
                Map.of("role", "ADMIN")).status()).isEqualTo(403);
    }

    @Test
    void adminRejectsUnknownRole() {
        Tokens target = register();
        assertThat(callAs(admin().access(), HttpMethod.PUT, "/api/v1/users/" + userId(target.access()) + "/role",
                Map.of("role", "GOD")).status()).isEqualTo(400);
    }

    @Test
    void roleChangeAppliesOnNextRefresh() {
        Tokens target = register();
        assertThat(callAs(admin().access(), HttpMethod.PUT, "/api/v1/users/" + userId(target.access()) + "/role",
                Map.of("role", "STORE_OPS")).status()).isEqualTo(200);

        assertThat(role(target.access())).as("existing token unchanged").isEqualTo("CUSTOMER");
        assertThat(role(refresh(target.refresh()).text("accessToken"))).isEqualTo("STORE_OPS");
    }
}
