package com.ims.inventory;

import java.util.List;
import java.util.UUID;

import com.ims.common.events.OrderLine;
import com.ims.inventory.StockReconciler.Drift;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;

class StockReconcilerTest extends IntegrationTestBase {

    private static final RedisScript<Long> RESERVE = RedisScript.of(new ClassPathResource("scripts/reserve.lua"), Long.class);

    @Autowired
    StockReconciler reconciler;

    @Autowired
    ReservationService reservations;

    final UUID store = UUID.randomUUID();
    final UUID milk = UUID.randomUUID();
    final UUID order = UUID.randomUUID();

    @Test
    void consistentStockReportsNothing() {
        stock(store, milk, 5);
        reservations.reserve(order, store, List.of(new OrderLine(milk, 2)));

        var report = reconciler.reconcile(true);

        assertThat(drifts(report.postgres())).isEmpty();
        assertThat(drifts(report.redis())).isEmpty();
    }

    @Test
    void reportOnlyModeChangesNothing() {
        stock(store, milk, 5);
        jdbc.update("UPDATE store_inventories SET reserved_stock = 3 WHERE store_id = ?", store);
        redis.opsForValue().set(StockKeys.stock(store, milk), "9");

        reconciler.reconcile(false);
        var report = reconciler.reconcile(false);

        assertThat(drifts(report.postgres())).containsExactly(new Drift(store, milk, -3, false));
        assertThat(drifts(report.redis())).containsExactly(new Drift(store, milk, -4, false));
        assertThat(reserved(store, milk)).isEqualTo(3);
        assertThat(sellable(store, milk)).isEqualTo(9);
    }

    @Test
    void repairsReservedStockFromTheLedger() {
        stock(store, milk, 5);
        reservations.reserve(order, store, List.of(new OrderLine(milk, 2)));
        jdbc.update("UPDATE store_inventories SET reserved_stock = 0 WHERE store_id = ?", store);

        var report = reconciler.reconcile(true);

        assertThat(drifts(report.postgres())).containsExactly(new Drift(store, milk, 2, true));
        assertThat(reserved(store, milk)).isEqualTo(2);
    }

    @Test
    void repairsRedisOnlyAfterTheSameDriftIsSeenTwice() {
        stock(store, milk, 5);
        redis.opsForValue().set(StockKeys.stock(store, milk), "8"); // e.g. a restock incremented Redis twice

        var first = reconciler.reconcile(true);
        assertThat(drifts(first.redis())).containsExactly(new Drift(store, milk, -3, false));
        assertThat(sellable(store, milk)).isEqualTo(8);

        var second = reconciler.reconcile(true);
        assertThat(drifts(second.redis())).containsExactly(new Drift(store, milk, -3, true));
        assertThat(sellable(store, milk)).isEqualTo(5);

        assertThat(drifts(reconciler.reconcile(true).redis())).isEmpty();
    }

    @Test
    void transientDriftThatChangesBetweenRunsIsNotRepaired() {
        stock(store, milk, 5);
        redis.opsForValue().set(StockKeys.stock(store, milk), "4");
        reconciler.reconcile(true);
        redis.opsForValue().set(StockKeys.stock(store, milk), "3");

        var report = reconciler.reconcile(true);

        assertThat(drifts(report.redis())).containsExactly(new Drift(store, milk, 2, false));
        assertThat(sellable(store, milk)).isEqualTo(3);
    }

    @Test
    void holdThatRedisLostIsStillCountedFromTheLedger() {
        stock(store, milk, 5);
        reservations.reserve(order, store, List.of(new OrderLine(milk, 2)));
        redis.delete(StockKeys.reservation(store, order)); // hash gone, counter still reflects the hold

        reconciler.reconcile(true);
        var report = reconciler.reconcile(true);

        assertThat(drifts(report.redis())).isEmpty();
        assertThat(sellable(store, milk)).isEqualTo(3);
    }

    @Test
    void holdOnlyInRedisIsNotSoldAgain() {
        stock(store, milk, 5);
        // Reserved in Redis, Postgres step never ran (in flight, or its record went to the DLT).
        redis.execute(RESERVE, List.of(StockKeys.reservation(store, order), StockKeys.stock(store, milk)), "60", "2");

        reconciler.reconcile(true);
        var report = reconciler.reconcile(true);

        assertThat(drifts(report.redis())).isEmpty();
        assertThat(sellable(store, milk)).isEqualTo(3);
    }

    @Test
    void lostCounterIsRestoredAfterARedisRestart() {
        stock(store, milk, 5);
        reservations.reserve(order, store, List.of(new OrderLine(milk, 2)));
        redis.delete(List.of(StockKeys.stock(store, milk), StockKeys.reservation(store, order)));

        reconciler.reconcile(true);
        reconciler.reconcile(true);

        assertThat(sellable(store, milk)).isEqualTo(3);
    }

    /** The reconciler scans every store; keep only this test's. */
    private List<Drift> drifts(List<Drift> all) {
        return all.stream().filter(d -> d.storeId().equals(store)).toList();
    }
}
