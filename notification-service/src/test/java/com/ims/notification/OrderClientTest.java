package com.ims.notification;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.UUID;

import com.ims.common.security.Caller;
import com.ims.common.security.Roles;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The ownership check against a stand-in order-service that answers like the real GET /api/v1/orders/{id}. */
class OrderClientTest {

    final UUID order = UUID.randomUUID();
    final UUID owner = UUID.randomUUID();

    HttpServer orderService;
    OrderClient client;

    @BeforeEach
    void start() throws IOException {
        orderService = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        orderService.createContext("/api/v1/orders/", exchange -> {
            boolean known = exchange.getRequestURI().getPath().endsWith(order.toString());
            boolean mine = owner.toString().equals(exchange.getRequestHeaders().getFirst(Caller.USER_ID_HEADER));
            exchange.sendResponseHeaders(!known ? 404 : mine ? 200 : 403, -1);
            exchange.close();
        });
        orderService.start();
        client = new OrderClient("http://localhost:" + orderService.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        orderService.stop(0);
    }

    @Test
    void ownerCanRead() {
        assertThat(client.canRead(order, new Caller(owner, Roles.CUSTOMER))).isTrue();
    }

    @Test
    void forbiddenMeansNo() {
        assertThat(client.canRead(order, new Caller(UUID.randomUUID(), Roles.CUSTOMER))).isFalse();
    }

    @Test
    void unknownOrderMeansNo() {
        assertThat(client.canRead(UUID.randomUUID(), new Caller(owner, Roles.CUSTOMER))).isFalse();
    }
}
