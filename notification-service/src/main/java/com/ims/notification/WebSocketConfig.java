package com.ims.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket. Clients subscribe to:
 *   /topic/orders/{orderId}          order progress + rider assignment
 *   /topic/orders/{orderId}/rider    live rider location
 *   /topic/stores/{storeId}/picking  dark store picker dashboard
 * Who may subscribe to what is decided by {@link SubscriptionGuard}.
 * The in-memory broker suits one node; use a STOMP broker relay (RabbitMQ) to scale out.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final SubscriptionGuard guard;
    private final String[] allowedOrigins;

    WebSocketConfig(SubscriptionGuard guard, @Value("${ims.ws.allowed-origins}") String[] allowedOrigins) {
        this.guard = guard;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins)
                .addInterceptors(new CallerHandshakeInterceptor());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(guard);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
