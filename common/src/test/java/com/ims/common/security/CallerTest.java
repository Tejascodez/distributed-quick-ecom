package com.ims.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CallerTest {

    private final UUID me = UUID.randomUUID();
    private final UUID someoneElse = UUID.randomUUID();

    @Test
    void requireAnyRoleAllowsListedRole() {
        assertThatNoException().isThrownBy(() -> new Caller(me, Roles.STORE_OPS).requireAnyRole(Roles.ADMIN, Roles.STORE_OPS));
    }

    @Test
    void requireAnyRoleRejectsOtherRolesWith403() {
        assertThatThrownBy(() -> new Caller(me, Roles.CUSTOMER).requireAnyRole(Roles.ADMIN))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void ownerPassesSelfOrRole() {
        assertThatNoException().isThrownBy(() -> new Caller(me, Roles.CUSTOMER).requireSelfOrRole(me, Roles.ADMIN));
    }

    @Test
    void privilegedRolePassesSelfOrRoleForOthers() {
        assertThatNoException().isThrownBy(() -> new Caller(me, Roles.ADMIN).requireSelfOrRole(someoneElse, Roles.ADMIN));
    }

    @Test
    void strangerFailsSelfOrRole() {
        assertThatThrownBy(() -> new Caller(me, Roles.CUSTOMER).requireSelfOrRole(someoneElse, Roles.ADMIN))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void roleMatchIsExactAndCaseSensitive() {
        assertThat(new Caller(me, "admin").hasAnyRole(Roles.ADMIN)).isFalse();
    }
}
