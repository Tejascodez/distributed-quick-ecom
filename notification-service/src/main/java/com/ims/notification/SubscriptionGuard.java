package com.ims.notification;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ims.common.security.Caller;
import com.ims.common.security.Roles;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * Checks every STOMP frame a client sends. Envoy only authenticates the connection; without this any logged-in user
 * could subscribe to another customer's order and follow the rider live. Throwing here makes Spring answer with an
 * ERROR frame and close the session.
 * <ul>
 *   <li>{@code /topic/orders/{id}} and {@code /topic/orders/{id}/rider}: the order's customer, store ops, admin</li>
 *   <li>{@code /topic/stores/{id}/picking}: store ops and admin (it lists every paid order and its customer)</li>
 *   <li>anything else, and every SEND: refused. Only the server publishes; the simple broker would otherwise relay
 *       a client's SEND to /topic/... straight to subscribers, letting anyone fake rider positions.</li>
 * </ul>
 */
@Component
class SubscriptionGuard implements ChannelInterceptor {

    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern ORDER_TOPIC = Pattern.compile("/topic/orders/(" + UUID_PATTERN + ")(/rider)?");
    private static final Pattern STORE_TOPIC = Pattern.compile("/topic/stores/" + UUID_PATTERN + "/picking");

    private final OrderOwners owners;

    SubscriptionGuard(OrderOwners owners) {
        this.owners = owners;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (accessor.getCommand() == StompCommand.SEND) {
            throw new MessagingException(message, "Clients cannot publish");
        }
        if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            String destination = accessor.getDestination();
            if (destination == null || !allowed(caller(accessor), destination)) {
                throw new MessagingException(message, "Not allowed to subscribe to " + destination);
            }
        }
        return message;
    }

    private boolean allowed(Caller caller, String destination) {
        if (caller == null) {
            return false;
        }
        Matcher order = ORDER_TOPIC.matcher(destination);
        if (order.matches()) {
            return caller.hasAnyRole(Roles.ADMIN, Roles.STORE_OPS)
                    || owners.isOwner(UUID.fromString(order.group(1)), caller);
        }
        return STORE_TOPIC.matcher(destination).matches() && caller.hasAnyRole(Roles.ADMIN, Roles.STORE_OPS);
    }

    private static Caller caller(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        return attributes == null ? null : (Caller) attributes.get(CallerHandshakeInterceptor.CALLER_ATTRIBUTE);
    }
}
