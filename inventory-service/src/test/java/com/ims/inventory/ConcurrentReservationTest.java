package com.ims.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.ims.common.events.OrderLine;
import com.ims.inventory.ReservationService.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Many checkouts racing for the last units of a product: none may be sold twice. */
class ConcurrentReservationTest extends IntegrationTestBase {

    @Autowired
    ReservationService reservations;

    final UUID store = UUID.randomUUID();
    final UUID milk = UUID.randomUUID();
    final UUID bread = UUID.randomUUID();

    @Test
    void fiveHundredBuyersForTenUnitsSellExactlyTen() throws Exception {
        stock(store, milk, 10);

        List<Result> results = race(500, () -> List.of(new OrderLine(milk, 1)));

        assertThat(results).filteredOn(r -> r == Result.RESERVED).hasSize(10);
        assertThat(results).filteredOn(r -> r == Result.OUT_OF_STOCK).hasSize(490);
        assertThat(sellable(store, milk)).isZero();
        assertThat(reserved(store, milk)).isEqualTo(10);
        assertThat(heldUnits(milk)).isEqualTo(10);
    }

    @Test
    void multiLineOrdersStayAllOrNothingUnderContention() throws Exception {
        stock(store, milk, 10);
        stock(store, bread, 5);

        // Bread runs out first; no order may keep its milk once it fails on bread.
        List<Result> results = race(200, () -> List.of(new OrderLine(milk, 1), new OrderLine(bread, 1)));

        assertThat(results).filteredOn(r -> r == Result.RESERVED).hasSize(5);
        assertThat(sellable(store, bread)).isZero();
        assertThat(sellable(store, milk)).isEqualTo(5);
        assertThat(reserved(store, milk)).isEqualTo(5);
        assertThat(reserved(store, bread)).isEqualTo(5);
    }

    @Test
    void racingReleasesAndReservesNeverOversell() throws Exception {
        stock(store, milk, 20);
        List<UUID> held = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID order = UUID.randomUUID();
            reservations.reserve(order, store, List.of(new OrderLine(milk, 1)));
            held.add(order);
        }

        // 20 cancellations free units while 300 new checkouts compete for them.
        var pool = Executors.newFixedThreadPool(64);
        var start = new CountDownLatch(1);
        List<Future<Result>> reserves = new ArrayList<>();
        for (UUID order : held) {
            pool.submit(() -> {
                start.await();
                reservations.release(order, store);
                return null;
            });
        }
        for (int i = 0; i < 300; i++) {
            reserves.add(pool.submit(() -> {
                start.await();
                return reservations.reserve(UUID.randomUUID(), store, List.of(new OrderLine(milk, 1)));
            }));
        }
        start.countDown();
        pool.shutdown();
        long won = 0;
        for (Future<Result> f : reserves) {
            won += f.get() == Result.RESERVED ? 1 : 0;
        }

        assertThat(won).isLessThanOrEqualTo(20);
        assertThat(sellable(store, milk)).isEqualTo(20 - won).isNotNegative();
        assertThat(reserved(store, milk)).isEqualTo((int) won);
        assertThat(heldUnits(milk)).isEqualTo(won);
    }

    /** Fires {@code buyers} reservations at once, each for a fresh order. */
    private List<Result> race(int buyers, Callable<List<OrderLine>> lines) throws Exception {
        var pool = Executors.newFixedThreadPool(64);
        var start = new CountDownLatch(1);
        List<Future<Result>> futures = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return reservations.reserve(UUID.randomUUID(), store, lines.call());
            }));
        }
        start.countDown();
        pool.shutdown();
        List<Result> results = new ArrayList<>();
        for (Future<Result> f : futures) {
            results.add(f.get());
        }
        return results;
    }

    private long heldUnits(UUID productId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM stock_reservations
                WHERE store_id = ? AND product_id = ? AND status = 'HELD'
                """, Long.class, store, productId);
    }
}
