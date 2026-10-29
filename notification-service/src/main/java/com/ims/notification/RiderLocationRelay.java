package com.ims.notification;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** Relays rider GPS pings (Redis Pub/Sub from delivery-service) to the customer's WebSocket. */
@Configuration
class RiderLocationRelay {

    private static final String CHANNEL_PREFIX = "rider-location:";

    @Bean
    RedisMessageListenerContainer riderLocationListener(RedisConnectionFactory connectionFactory,
                                                        SimpMessagingTemplate ws) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String orderId = channel.substring(CHANNEL_PREFIX.length());
            ws.convertAndSend("/topic/orders/" + orderId + "/rider", new String(message.getBody(), StandardCharsets.UTF_8));
        }, new PatternTopic(CHANNEL_PREFIX + "*"));
        return container;
    }
}
