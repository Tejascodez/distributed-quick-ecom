package com.ims.common.security;

import java.util.Arrays;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The authenticated user behind a request. Envoy validates the JWT and sets X-User-Id / X-User-Role
 * from its claims; services trust those headers, so they must only be reachable through Envoy.
 * Declare it as a controller parameter to receive it.
 */
public record Caller(UUID userId, String role) {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String ROLE_HEADER = "X-User-Role";

    public boolean hasAnyRole(String... roles) {
        return Arrays.asList(roles).contains(role);
    }

    /** 403 unless the caller has one of the roles. */
    public void requireAnyRole(String... roles) {
        if (!hasAnyRole(roles)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requires role " + String.join(" or ", roles));
        }
    }

    /** 403 unless the caller is the resource owner or has one of the roles. */
    public void requireSelfOrRole(UUID ownerId, String... roles) {
        if (!userId.equals(ownerId) && !hasAnyRole(roles)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your resource");
        }
    }
}
