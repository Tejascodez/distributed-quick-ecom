package com.ims.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ims.delivery.DispatchService.Outcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchTest extends IntegrationTestBase {

    final UUID order = UUID.randomUUID();

    @Test
    void assignsTheNearestIdleRider() {
        UUID far = onlineRider(2.0);
        UUID near = onlineRider(0.5);

        assertThat(dispatch.tryAssign(order, store)).isEqualTo(Outcome.DONE);

        assertThat(assignedRider(order)).isEqualTo(near);
        assertThat(marker(near)).isEqualTo(order.toString());
        assertThat(redis.getExpire(RiderKeys.activeOrder(near))).isEqualTo(-1); // committed: no claim TTL left
        assertThat(idle(near)).isFalse();
        assertThat(idle(far)).isTrue();
        assertThat(partnerStatus(near)).isEqualTo("ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT event_published_at IS NOT NULL FROM delivery_assignments WHERE order_id = ?",
                Boolean.class, order)).isTrue();
    }

    @Test
    void noRiderInRangeWaits() {
        onlineRider(10.0); // outside the 3 km radius

        assertThat(dispatch.tryAssign(order, store)).isEqualTo(Outcome.NO_RIDER);
        assertThat(assignedRider(order)).isNull();
    }

    @Test
    void redeliveredOrderPaidDoesNotTakeASecondRider() {
        onlineRider(0.5);
        UUID second = onlineRider(0.7);
        dispatch.tryAssign(order, store);

        assertThat(dispatch.tryAssign(order, store)).isEqualTo(Outcome.DONE);

        assertThat(idle(second)).isTrue();
    }

    @Test
    void pingWhileClaimedDoesNotPutTheRiderBackInThePool() {
        UUID rider = onlineRider(0.5);
        assertThat(pool.claim(rider, order)).isTrue();

        assertThat(ping(rider, 0.5)).isEqualTo(order);

        assertThat(idle(rider)).isFalse();
        assertThat(pool.claim(rider, UUID.randomUUID())).isFalse();
    }

    @Test
    void claimThatNeverCommitsFreesTheRiderAfterItsTtl() {
        UUID rider = onlineRider(0.5);

        pool.claim(rider, order);

        assertThat(redis.getExpire(RiderKeys.activeOrder(rider))).isBetween(1L, 120L);
    }

    @Test
    void offlineRiderPingsDoNotRejoinThePool() {
        UUID rider = onlineRider(0.5);

        pool.offline(rider);
        ping(rider, 0.5);

        assertThat(idle(rider)).isFalse();
        assertThat(dispatch.tryAssign(order, store)).isEqualTo(Outcome.NO_RIDER);
    }

    @Test
    void silentRiderIsDroppedFromThePool() {
        UUID rider = onlineRider(0.5);
        redis.opsForZSet().add(RiderKeys.LAST_SEEN, rider.toString(), 0); // last ping long ago

        pool.dropSilentRiders();

        assertThat(idle(rider)).isFalse();
        assertThat(dispatch.tryAssign(order, store)).isEqualTo(Outcome.NO_RIDER);
        ping(rider, 0.5);
        assertThat(idle(rider)).isTrue(); // pinging again brings them back
    }

    @Test
    void markerLostByRedisIsRestoredFromPostgres() {
        UUID rider = onlineRider(0.5);
        dispatch.tryAssign(order, store);
        redis.delete(RiderKeys.activeOrder(rider));

        pool.restoreMarkers();

        assertThat(marker(rider)).isEqualTo(order.toString());
        ping(rider, 0.5);
        assertThat(idle(rider)).isFalse();
    }

    @Test
    void busyRiderThatRedisForgotIsNotAssignedTwice() {
        UUID rider = onlineRider(0.5);
        dispatch.tryAssign(order, store);
        redis.delete(RiderKeys.activeOrder(rider)); // Redis restarted...
        ping(rider, 0.5);                           // ...so the busy rider looks idle
        assertThat(idle(rider)).isTrue();

        UUID next = UUID.randomUUID();
        assertThat(dispatch.tryAssign(next, store)).isEqualTo(Outcome.NO_RIDER);

        assertThat(assignedRider(next)).isNull();
        assertThat(marker(rider)).isEqualTo(order.toString()); // Postgres put the real order back
    }

    @Test
    void markerWithNoOpenAssignmentIsCleared() {
        UUID rider = onlineRider(0.5);
        redis.opsForValue().set(RiderKeys.activeOrder(rider), UUID.randomUUID().toString()); // leaked, no TTL

        pool.restoreMarkers();

        assertThat(marker(rider)).isNull();
    }

    @Test
    void starvedStoreDoesNotBlockOtherStores() {
        UUID starved = UUID.randomUUID();
        STORES.put(starved, new StoreClient.StoreLocation(starved, -storeLat, storeLng)); // no riders there
        List<UUID> waiting = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            UUID o = UUID.randomUUID();
            dispatch.enqueue(o, starved);
            waiting.add(o);
        }
        UUID rider = onlineRider(0.5);
        dispatch.enqueue(order, store); // queued behind all 60

        dispatch.drain();

        assertThat(assignedRider(order)).isEqualTo(rider);
        waiting.forEach(o -> assertThat(assignedRider(o)).isNull());
        waiting.forEach(o -> dispatch.cancel(o, starved)); // tidy the shared queue
    }

    @Test
    void failingStoreLookupDoesNotStopTheDrain() {
        UUID unreachable = UUID.randomUUID(); // not in STORES: the mocked lookup throws
        dispatch.enqueue(UUID.randomUUID(), unreachable);
        UUID rider = onlineRider(0.5);
        dispatch.enqueue(order, store);

        dispatch.drain();

        assertThat(assignedRider(order)).isEqualTo(rider);
    }

    @Test
    void cancelledBeforeDispatchIsNeverAssigned() {
        UUID rider = onlineRider(0.5);
        dispatch.enqueue(order, store);

        dispatch.cancel(order, store);

        assertThat(dispatch.tryAssign(order, store)).isEqualTo(Outcome.DONE);
        assertThat(assignedRider(order)).isNull();
        assertThat(idle(rider)).isTrue();
        assertThat(redis.opsForZSet().score(RiderKeys.PENDING, order + "|" + store)).isNull();
    }

    @Test
    void cancelledAfterAssignmentFreesTheRider() {
        UUID rider = onlineRider(0.5);
        dispatch.tryAssign(order, store);

        dispatch.cancel(order, store);

        assertThat(assignedRider(order)).isNull();
        assertThat(marker(rider)).isNull();
        assertThat(partnerStatus(rider)).isEqualTo("IDLE");
        ping(rider, 0.5);
        assertThat(idle(rider)).isTrue();
    }

    /** order-service has already refunded the customer, so the cancel wins and the rider returns the order. */
    @Test
    void cancelledAfterPickupEndsTheDeliveryToo() {
        UUID rider = onlineRider(0.5);
        dispatch.tryAssign(order, store);
        jdbc.update("UPDATE delivery_assignments SET picked_up_at = now() WHERE order_id = ?", order);

        dispatch.cancel(order, store);

        assertThat(assignedRider(order)).isNull();
        assertThat(marker(rider)).isNull();
        assertThat(partnerStatus(rider)).isEqualTo("IDLE");
    }

    /**
     * Two dispatchers drain the same queue while every rider pings constantly. Each rider must end up with at
     * most one order, and Redis must agree with Postgres about who is busy.
     */
    @Test
    void racingDispatchersAndPingsNeverDoubleBookARider() {
        List<UUID> riders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            riders.add(onlineRider(0.2 + i * 0.1));
        }
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            UUID o = UUID.randomUUID();
            orders.add(o);
            dispatch.enqueue(o, store);
        }
        var stop = new AtomicBoolean();
        var pinger = CompletableFuture.runAsync(() -> {
            while (!stop.get()) {
                riders.forEach(r -> ping(r, 0.3));
            }
        });

        CompletableFuture.allOf(CompletableFuture.runAsync(dispatch::drain), CompletableFuture.runAsync(dispatch::drain),
                CompletableFuture.runAsync(dispatch::drain)).join();
        stop.set(true);
        pinger.join();

        List<UUID> assigned = orders.stream().map(this::assignedRider).filter(r -> r != null).toList();
        assertThat(assigned).hasSize(10).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(riders);
        for (UUID o : orders) {
            UUID r = assignedRider(o);
            if (r != null) {
                assertThat(marker(r)).isEqualTo(o.toString());
                assertThat(idle(r)).isFalse();
            }
        }
        orders.forEach(o -> dispatch.cancel(o, store)); // tidy the shared queue
    }
}
