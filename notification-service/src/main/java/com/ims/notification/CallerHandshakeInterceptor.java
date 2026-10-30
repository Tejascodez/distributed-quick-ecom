package com.ims.notification;

import java.util.Map;
import java.util.UUID;

import com.ims.common.security.Caller;
import com.ims.common.security.Roles;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Envoy validates the JWT on the WebSocket upgrade and sets X-User-Id / X-User-Role from its claims, like on any
 * other request. This keeps that identity on the session for {@link SubscriptionGuard}; upgrades without one are
 * refused.
 */
class CallerHandshakeInterceptor implements HandshakeInterceptor {

    static final String CALLER_ATTRIBUTE = "caller";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String userId = request.getHeaders().getFirst(Caller.USER_ID_HEADER);
        String role = request.getHeaders().getFirst(Caller.ROLE_HEADER);
        try {
            attributes.put(CALLER_ATTRIBUTE, new Caller(UUID.fromString(userId), role == null ? Roles.CUSTOMER : role));
            return true;
        } catch (NullPointerException | IllegalArgumentException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                               Exception exception) {
    }
}
