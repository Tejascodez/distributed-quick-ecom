package com.ims.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.Jwt;

class AuthFlowIntegrationTest extends IntegrationTestBase {

    @Test
    void registerIssuesTokensVerifiableWithTheJwks() {
        Response r = call(HttpMethod.POST, "/api/v1/auth/register",
                Map.of("fullName", "Asha", "phone", randomPhone(), "password", "password123"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().path("expiresIn").asLong()).isEqualTo(900);
        Jwt jwt = envoy.decode(r.text("accessToken"));
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("ims-auth"); // StringOrURI, not a URL
        assertThat(jwt.getClaimAsString("role")).isEqualTo("CUSTOMER");
        assertThat(r.text("refreshToken")).hasSize(43); // 256 bits, base64url
    }

    @Test
    void duplicatePhoneIsRejected() {
        String phone = randomPhone();
        register(phone);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/register",
                Map.of("fullName", "Dup", "phone", phone, "password", "password123")).status()).isEqualTo(409);
    }

    @Test
    void shortPasswordIsRejected() {
        assertThat(call(HttpMethod.POST, "/api/v1/auth/register",
                Map.of("fullName", "X", "phone", randomPhone(), "password", "short")).status()).isEqualTo(400);
    }

    @Test
    void loginWithWrongPasswordIs401() {
        String phone = randomPhone();
        register(phone);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/login",
                Map.of("phone", phone, "password", "wrong-password")).status()).isEqualTo(401);
    }

    @Test
    void refreshRotatesTheToken() {
        Tokens t = register();
        Response r = refresh(t.refresh());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("refreshToken")).isNotEqualTo(t.refresh());
        assertThat(userId(r.text("accessToken"))).isEqualTo(userId(t.access()));
    }

    @Test
    void reusingAConsumedRefreshTokenRevokesEverySession() {
        Tokens t = register();
        String rotated = refresh(t.refresh()).text("refreshToken");

        assertThat(refresh(t.refresh()).status()).as("replayed stolen copy").isEqualTo(401);
        assertThat(refresh(rotated).status()).as("legit token revoked by the alarm").isEqualTo(401);
    }

    @Test
    void unknownRefreshTokenIs401() {
        assertThat(refresh("not-a-real-token").status()).isEqualTo(401);
    }

    @Test
    void logoutEndsOnlyThatSession() {
        String phone = randomPhone();
        register(phone);
        Tokens phoneSession = login(phone);
        Tokens laptopSession = login(phone);

        assertThat(call(HttpMethod.POST, "/api/v1/auth/logout", Map.of("refreshToken", phoneSession.refresh())).status())
                .isEqualTo(204);
        assertThat(refresh(phoneSession.refresh()).status()).isEqualTo(401);
        assertThat(refresh(laptopSession.refresh()).status()).isEqualTo(200);
    }

    @Test
    void logoutIsIdempotent() {
        assertThat(call(HttpMethod.POST, "/api/v1/auth/logout", Map.of("refreshToken", "nope")).status()).isEqualTo(204);
    }

    @Test
    void logoutEverywhereRevokesAllSessions() {
        String phone = randomPhone();
        Tokens a = register(phone);
        Tokens b = login(phone);

        assertThat(callAs(a.access(), HttpMethod.DELETE, "/api/v1/users/me/sessions", null).status()).isEqualTo(204);
        assertThat(refresh(a.refresh()).status()).isEqualTo(401);
        assertThat(refresh(b.refresh()).status()).isEqualTo(401);
    }
}
