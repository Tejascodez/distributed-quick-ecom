package com.ims.notification;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.ims.common.events.Topics;
import com.ims.common.security.Caller;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs notification-service against throwaway Redis and Kafka containers, with order-service mocked, and talks to it
 * over a real WebSocket the way a browser behind Envoy would (identity in the upgrade request headers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.consumer.auto-offset-reset=earliest")
@SuppressWarnings("resource") // shared containers live for the whole JVM; the reaper stops them
abstract class IntegrationTestBase {

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.6.8-alpine").withExposedPorts(6379);

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

    static {
        REDIS.start();
        KAFKA.start();
        createTopics();
    }

    /** Topics exist before the listeners subscribe, so the first test doesn't wait on auto-creation. */
    private static void createTopics() {
        List<String> names = List.of(Topics.ORDER_STATUS_CHANGED, Topics.ORDER_PAID, Topics.RIDER_ASSIGNED,
                Topics.ORDER_STATUS_CHANGED + "-dlt", Topics.ORDER_PAID + "-dlt", Topics.RIDER_ASSIGNED + "-dlt");
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(names.stream().map(n -> new NewTopic(n, 1, (short) 1)).toList()).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create test topics", e);
        }
    }

    /** Hands every frame body over as text, whatever its content type (events are JSON, rider pings plain text). */
    private static final MessageConverter TEXT = new MessageConverter() {
        @Override
        public Object fromMessage(Message<?> message, Class<?> targetClass) {
            return new String((byte[]) message.getPayload(), StandardCharsets.UTF_8);
        }

        @Override
        public Message<?> toMessage(Object payload, MessageHeaders headers) {
            return MessageBuilder.createMessage(payload.toString().getBytes(StandardCharsets.UTF_8), headers);
        }
    };

    @MockitoBean
    OrderClient orderClient;

    @Autowired
    StringRedisTemplate redis;

    /** The server's own publisher, used to probe when a subscription is live. */
    @Autowired
    SimpMessagingTemplate server;

    @Value("${local.server.port}")
    int port;

    /** A connected client and the ERROR frames the server sent it. */
    record Client(StompSession session, BlockingQueue<String> errors) {

        /** Subscribes and returns the bodies that arrive on {@code destination}. */
        BlockingQueue<String> subscribe(String destination) {
            BlockingQueue<String> received = new LinkedBlockingQueue<>();
            session.subscribe(destination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return String.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    received.add((String) payload);
                }
            });
            return received;
        }
    }

    Client connect(UUID user, String role) {
        var headers = new WebSocketHttpHeaders();
        headers.add(Caller.USER_ID_HEADER, user.toString());
        headers.add(Caller.ROLE_HEADER, role);
        return connect(headers);
    }

    Client connect(WebSocketHttpHeaders headers) {
        try {
            return connectAsync(headers).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not connect", e);
        }
    }

    CompletableFuture<Client> connectAsync(WebSocketHttpHeaders headers) {
        var stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(TEXT);
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        return stomp.connectAsync("ws://localhost:" + port + "/ws", headers, new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errors.add(String.valueOf(headers.getFirst("message")));
            }
        }).thenApply(session -> new Client(session, errors));
    }

    /** Subscribes and waits until the subscription is live, so nothing sent afterwards can be missed. */
    BlockingQueue<String> subscribed(Client client, String destination) {
        BlockingQueue<String> received = client.subscribe(destination);
        await().atMost(TIMEOUT).until(() -> {
            server.convertAndSend(destination, "probe");
            return received.poll(200, TimeUnit.MILLISECONDS) != null;
        });
        await().pollDelay(Duration.ofMillis(300)).until(() -> true); // let in-flight probes land...
        received.clear();                                             // ...then forget them
        return received;
    }

    /** Waits for the server to reject the client: an ERROR frame naming {@code reason}, then a closed session. */
    void assertRejected(Client client, String reason) {
        await().atMost(TIMEOUT).until(() -> !client.session().isConnected());
        assertThat(client.errors()).anyMatch(e -> e.contains(reason));
    }

    static String orderTopic(UUID order) {
        return "/topic/orders/" + order;
    }
}
