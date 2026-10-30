package com.ims.inventory;

import java.util.List;
import java.util.UUID;

import com.ims.common.events.OrderLine;
import com.ims.inventory.ReservationService.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationServiceTest extends IntegrationTestBase {

    private static final RedisScript<Long> RESERVE = RedisScript.of(new ClassPathResource("scripts/reserve.lua"), Long.class);

    @Autowired
    ReservationService reservations;

    final UUID store = UUID.randomUUID();
    final UUID milk = UUID.randomUUID();
    final UUID bread = UUID.randomUUID();
    final UUID order = UUID.randomUUID();

    @Test
    void reservesAndRecordsTheHold() {
        stock(store, milk, 5);

        assertThat(reservations.reserve(order, store, List.of(new OrderLine(milk, 2)))).isEqualTo(Result.RESERVED);

        assertThat(sellable(store, milk)).isEqualTo(3);
        assertThat(reserved(store, milk)).isEqualTo(2);
        assertThat(holdStatus(order)).isEqualTo("HELD");
    }

    @Test
    void duplicateLinesAreCheckedAgainstStockAsOne() {
        stock(store, milk, 3);

        var result = reservations.reserve(order, store, List.of(new OrderLine(milk, 2), new OrderLine(milk, 2)));

        assertThat(result).isEqualTo(Result.OUT_OF_STOCK);
        assertThat(sellable(store, milk)).isEqualTo(3);
        assertThat(holdStatus(order)).isNull();
    }

    @Test
    void duplicateLinesReleaseEveryUnitTheyHeld() {
        stock(store, milk, 4);

        reservations.reserve(order, store, List.of(new OrderLine(milk, 2), new OrderLine(milk, 2)));
        assertThat(sellable(store, milk)).isZero();
        reservations.release(order, store);

        assertThat(sellable(store, milk)).isEqualTo(4);
        assertThat(reserved(store, milk)).isZero();
    }

    @Test
    void luaRejectsRepeatedKeysAndBadQuantitiesEvenWithoutTheJavaMerge() {
        stock(store, milk, 3);
        String stockKey = StockKeys.stock(store, milk);
        var keys = List.of(StockKeys.reservation(store, order), stockKey, stockKey);

        assertThatThrownBy(() -> redis.execute(RESERVE, keys, "60", "2", "2")).rootCause().hasMessageContaining("invalid");
        assertThatThrownBy(() -> redis.execute(RESERVE, List.of(keys.get(0), stockKey), "60", "-5"))
                .rootCause().hasMessageContaining("invalid");
        assertThat(sellable(store, milk)).isEqualTo(3);
    }

    @Test
    void invalidLinesAreRejectedBeforeTouchingStock() {
        stock(store, milk, 3);

        assertThatThrownBy(() -> reservations.reserve(order, store, List.of(new OrderLine(milk, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservations.reserve(order, store, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sellable(store, milk)).isEqualTo(3);
    }

    @Test
    void reserveIsAllOrNothing() {
        stock(store, milk, 5);
        stock(store, bread, 1);

        var result = reservations.reserve(order, store, List.of(new OrderLine(milk, 2), new OrderLine(bread, 2)));

        assertThat(result).isEqualTo(Result.OUT_OF_STOCK);
        assertThat(sellable(store, milk)).isEqualTo(5);
        assertThat(sellable(store, bread)).isEqualTo(1);
    }

    @Test
    void redeliveryDoesNotHoldTwice() {
        stock(store, milk, 5);
        var lines = List.of(new OrderLine(milk, 2));

        reservations.reserve(order, store, lines);
        assertThat(reservations.reserve(order, store, lines)).isEqualTo(Result.ALREADY_RESERVED);

        assertThat(sellable(store, milk)).isEqualTo(3);
        assertThat(reserved(store, milk)).isEqualTo(2);
    }

    @Test
    void retryRecordsTheHoldWhenThePreviousAttemptStoppedAfterRedis() {
        stock(store, milk, 5);
        // The first delivery reserved in Redis, then its Postgres step failed.
        redis.execute(RESERVE, List.of(StockKeys.reservation(store, order), StockKeys.stock(store, milk)), "60", "2");

        assertThat(reservations.reserve(order, store, List.of(new OrderLine(milk, 2)))).isEqualTo(Result.ALREADY_RESERVED);

        assertThat(sellable(store, milk)).isEqualTo(3);
        assertThat(reserved(store, milk)).isEqualTo(2);
        assertThat(holdStatus(order)).isEqualTo("HELD");
    }

    @Test
    void confirmDeductsOnceEvenWhenRedelivered() {
        stock(store, milk, 5);
        reservations.reserve(order, store, List.of(new OrderLine(milk, 2)));

        reservations.confirm(order, store);
        reservations.confirm(order, store);

        assertThat(available(store, milk)).isEqualTo(3);
        assertThat(reserved(store, milk)).isZero();
        assertThat(sellable(store, milk)).isEqualTo(3);
        assertThat(holdStatus(order)).isEqualTo("CONFIRMED");
    }

    @Test
    void releaseReturnsStockOnceEvenWhenRedelivered() {
        stock(store, milk, 5);
        reservations.reserve(order, store, List.of(new OrderLine(milk, 2)));

        reservations.release(order, store);
        reservations.release(order, store);

        assertThat(sellable(store, milk)).isEqualTo(5);
        assertThat(reserved(store, milk)).isZero();
        assertThat(available(store, milk)).isEqualTo(5);
    }

    @Test
    void releaseAfterConfirmChangesNothing() {
        stock(store, milk, 5);
        reservations.reserve(order, store, List.of(new OrderLine(milk, 2)));
        reservations.confirm(order, store);

        reservations.release(order, store);

        assertThat(available(store, milk)).isEqualTo(3);
        assertThat(sellable(store, milk)).isEqualTo(3);
        assertThat(holdStatus(order)).isEqualTo("CONFIRMED");
    }

    @Test
    void confirmAfterReleaseDoesNotDeduct() {
        stock(store, milk, 5);
        reservations.reserve(order, store, List.of(new OrderLine(milk, 2)));
        reservations.release(order, store);

        reservations.confirm(order, store);

        assertThat(available(store, milk)).isEqualTo(5);
        assertThat(sellable(store, milk)).isEqualTo(5);
        assertThat(holdStatus(order)).isEqualTo("RELEASED");
    }

    @Test
    void lateOrderCreatedAfterReleaseDoesNotHoldStockAgain() {
        stock(store, milk, 5);
        var lines = List.of(new OrderLine(milk, 2));
        reservations.reserve(order, store, lines);
        reservations.release(order, store);

        reservations.reserve(order, store, lines);

        assertThat(sellable(store, milk)).isEqualTo(5);
        assertThat(reserved(store, milk)).isZero();
    }
}
