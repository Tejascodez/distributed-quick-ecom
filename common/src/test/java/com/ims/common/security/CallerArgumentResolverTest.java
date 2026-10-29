package com.ims.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;

class CallerArgumentResolverTest {

    private final CallerArgumentResolver resolver = new CallerArgumentResolver();

    @SuppressWarnings("unused")
    void handler(Caller caller, String other) {
    }

    private MethodParameter param(int index) throws NoSuchMethodException {
        return new MethodParameter(getClass().getDeclaredMethod("handler", Caller.class, String.class), index);
    }

    private Caller resolve(MockHttpServletRequest request) throws Exception {
        return resolver.resolveArgument(param(0), null, new ServletWebRequest(request), null);
    }

    @Test
    void supportsOnlyCallerParameters() throws Exception {
        assertThat(resolver.supportsParameter(param(0))).isTrue();
        assertThat(resolver.supportsParameter(param(1))).isFalse();
    }

    @Test
    void buildsCallerFromEnvoyHeaders() throws Exception {
        UUID id = UUID.randomUUID();
        var request = new MockHttpServletRequest();
        request.addHeader(Caller.USER_ID_HEADER, id.toString());
        request.addHeader(Caller.ROLE_HEADER, Roles.ADMIN);
        assertThat(resolve(request)).isEqualTo(new Caller(id, Roles.ADMIN));
    }

    @Test
    void missingRoleDefaultsToLeastPrivilege() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(Caller.USER_ID_HEADER, UUID.randomUUID().toString());
        assertThat(resolve(request).role()).isEqualTo(Roles.CUSTOMER);
    }

    @Test
    void missingUserIdIs401() {
        assertThatThrownBy(() -> resolve(new MockHttpServletRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void malformedUserIdIs401() {
        var request = new MockHttpServletRequest();
        request.addHeader(Caller.USER_ID_HEADER, "not-a-uuid");
        assertThatThrownBy(() -> resolve(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
