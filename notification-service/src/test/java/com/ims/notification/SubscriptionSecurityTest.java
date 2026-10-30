package com.ims.notification;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.ims.common.security.Caller;
import com.ims.common.security.Roles;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketHttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Who may subscribe to, and publish on, which STOMP destination. */
class SubscriptionSecurityTest extends IntegrationTestBase {

    final UUID order = UUID.randomUUID();
    final UUID owner = UUID.randomUUID();
    final UUID stranger = UUID.randomUUID();

    @Test
    void connectionWithoutIdentityIsRefused() {
        assertThatThrownBy(() -> connectAsync(new WebSocketHttpHeaders()).get(10, TimeUnit.SECONDS))
                .hasStackTraceContaining("401");
    }

    @Test
    void connectionFromAnotherSiteIsRefused() {
        var headers = new WebSocketHttpHeaders();
        headers.add(Caller.USER_ID_HEADER, owner.toString());
        headers.setOrigin("https://evil.example");

        assertThatThrownBy(() -> connectAsync(headers).get(10, TimeUnit.SECONDS)).hasStackTraceContaining("403");
    }

    @Test
    void ownerKnownFromEventsCanWatchTheirRider() {
        new OrderOwners(redis, orderClient).remember(order, owner);

        subscribed(connect(owner, Roles.CUSTOMER), orderTopic(order) + "/rider");

        verify(orderClient, never()).canRead(any(), any());
    }

    @Test
    void strangerCannotWatchSomeoneElsesRider() {
        new OrderOwners(redis, orderClient).remember(order, owner);
        Client client = connect(stranger, Roles.CUSTOMER);

        client.subscribe(orderTopic(order) + "/rider");

        assertRejected(client, "Not allowed");
        verify(orderClient, never()).canRead(any(), any()); // the cached owner answers it
    }

    @Test
    void ownerOfABrandNewOrderIsCheckedWithOrderServiceOnce() {
        Caller caller = new Caller(owner, Roles.CUSTOMER);
        given(orderClient.canRead(order, caller)).willReturn(true);

        subscribed(connect(owner, Roles.CUSTOMER), orderTopic(order));

        assertThat(redis.opsForValue().get(OrderOwners.key(order))).isEqualTo(owner.toString());
        subscribed(connect(owner, Roles.CUSTOMER), orderTopic(order) + "/rider");
        verify(orderClient).canRead(order, caller);
    }

    @Test
    void orderThatOrderServiceWontShowIsRefused() {
        given(orderClient.canRead(any(), any())).willReturn(false);
        Client client = connect(stranger, Roles.CUSTOMER);

        client.subscribe(orderTopic(order));

        assertRejected(client, "Not allowed");
        assertThat(redis.opsForValue().get(OrderOwners.key(order))).isNull();
    }

    @Test
    void staffCanWatchAnyOrder() {
        subscribed(connect(UUID.randomUUID(), Roles.STORE_OPS), orderTopic(order) + "/rider");
        subscribed(connect(UUID.randomUUID(), Roles.ADMIN), orderTopic(order));

        verify(orderClient, never()).canRead(any(), any());
    }

    @Test
    void customerCannotSeeTheStorePickingFeed() {
        Client client = connect(owner, Roles.CUSTOMER);

        client.subscribe("/topic/stores/" + UUID.randomUUID() + "/picking");

        assertRejected(client, "Not allowed");
    }

    @Test
    void riderCannotSeeTheStorePickingFeed() {
        Client client = connect(UUID.randomUUID(), Roles.RIDER);

        client.subscribe("/topic/stores/" + UUID.randomUUID() + "/picking");

        assertRejected(client, "Not allowed");
    }

    @Test
    void unknownDestinationIsRefusedEvenForAdmins() {
        Client client = connect(UUID.randomUUID(), Roles.ADMIN);

        client.subscribe("/topic/orders/*");

        assertRejected(client, "Not allowed");
    }

    /** The simple broker relays a client's SEND to subscribers, so without the guard anyone could fake positions. */
    @Test
    void clientCannotPublishFakeRiderPositions() throws InterruptedException {
        new OrderOwners(redis, orderClient).remember(order, owner);
        BlockingQueue<String> ownerView = subscribed(connect(owner, Roles.CUSTOMER), orderTopic(order) + "/rider");
        Client attacker = connect(stranger, Roles.CUSTOMER);

        attacker.session().send(orderTopic(order) + "/rider", "{\"lat\":0,\"lng\":0}");

        assertRejected(attacker, "cannot publish");
        assertThat(ownerView.poll(Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS)).isNull();
    }
}
