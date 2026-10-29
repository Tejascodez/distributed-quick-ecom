package com.ims.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class AddressIntegrationTest extends IntegrationTestBase {

    private static final String ADDRESSES = "/api/v1/users/me/addresses";

    private static Map<String, Object> address(String label) {
        return Map.of("label", label, "line1", "12 Hill Rd", "city", "Mumbai", "pincode", "400050",
                "lat", 19.055, "lng", 72.834);
    }

    private String create(Tokens t, String label) {
        return callAs(t.access(), HttpMethod.POST, ADDRESSES, address(label)).text("id");
    }

    private Map<String, Boolean> defaults(Tokens t) {
        Map<String, Boolean> result = new HashMap<>();
        callAs(t.access(), HttpMethod.GET, ADDRESSES, null).body()
                .forEach(a -> result.put(a.path("label").asString(), a.path("isDefault").asBoolean()));
        return result;
    }

    @Test
    void firstAddressBecomesDefault() {
        Tokens t = register();
        create(t, "Home");
        create(t, "Work");
        assertThat(defaults(t)).containsEntry("Home", true).containsEntry("Work", false);
    }

    @Test
    void makingAnotherDefaultClearsThePreviousOne() {
        Tokens t = register();
        create(t, "Home");
        String work = create(t, "Work");

        assertThat(callAs(t.access(), HttpMethod.POST, ADDRESSES + "/" + work + "/default", null).status()).isEqualTo(200);
        assertThat(defaults(t)).containsEntry("Home", false).containsEntry("Work", true);
    }

    @Test
    void deletingTheDefaultPromotesAnother() {
        Tokens t = register();
        String home = create(t, "Home");
        create(t, "Work");

        assertThat(callAs(t.access(), HttpMethod.DELETE, ADDRESSES + "/" + home, null).status()).isEqualTo(204);
        assertThat(defaults(t)).containsOnly(Map.entry("Work", true));
    }

    @Test
    void updateChangesFields() {
        Tokens t = register();
        String id = create(t, "Home");
        Map<String, Object> changed = new HashMap<>(address("Home"));
        changed.put("city", "Pune");

        assertThat(callAs(t.access(), HttpMethod.PUT, ADDRESSES + "/" + id, changed).text("city")).isEqualTo("Pune");
    }

    @Test
    void otherUsersCannotSeeOrTouchMyAddress() {
        Tokens owner = register();
        Tokens stranger = register();
        String id = create(owner, "Home");

        assertThat(callAs(stranger.access(), HttpMethod.GET, ADDRESSES, null).body().size()).isZero();
        assertThat(callAs(stranger.access(), HttpMethod.PUT, ADDRESSES + "/" + id, address("Mine")).status()).isEqualTo(404);
        assertThat(callAs(stranger.access(), HttpMethod.DELETE, ADDRESSES + "/" + id, null).status()).isEqualTo(404);
        assertThat(callAs(stranger.access(), HttpMethod.POST, ADDRESSES + "/" + id + "/default", null).status()).isEqualTo(404);
    }

    @Test
    void invalidAddressIs400() {
        Tokens t = register();
        Map<String, Object> bad = new HashMap<>(address("Home"));
        bad.put("pincode", "12");
        assertThat(callAs(t.access(), HttpMethod.POST, ADDRESSES, bad).status()).isEqualTo(400);
        bad = new HashMap<>(address("Home"));
        bad.put("lat", 120);
        assertThat(callAs(t.access(), HttpMethod.POST, ADDRESSES, bad).status()).isEqualTo(400);
    }

    @Test
    void missingIdentityIs401() {
        assertThat(call(HttpMethod.GET, ADDRESSES, null).status()).isEqualTo(401);
    }

    @Test
    void internalLookupOnlyResolvesTheOwnersAddress() {
        Tokens owner = register();
        String id = create(owner, "Home");
        String ownerId = userId(owner.access());

        Response found = call(HttpMethod.GET, "/internal/users/" + ownerId + "/addresses/" + id, null);
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.body().path("lat").asDouble()).isEqualTo(19.055);

        String otherId = userId(register().access());
        assertThat(call(HttpMethod.GET, "/internal/users/" + otherId + "/addresses/" + id, null).status()).isEqualTo(404);
    }
}
