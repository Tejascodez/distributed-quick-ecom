package com.ims.order;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox: events are written in the same DB transaction as the state change,
 * and Debezium streams them from the WAL to Kafka. No dual write, no lost events.
 */
@Component
class Outbox {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    Outbox(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void publish(String topic, UUID orderId, Object event) {
        jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, 'Order', ?, ?, ?::jsonb)
                """, UUID.randomUUID(), orderId.toString(), topic, json.writeValueAsString(event));
    }

    /** Debezium reads from the WAL, so published rows only need to live briefly. */
    @Scheduled(cron = "0 0 * * * *")
    void purge() {
        jdbc.update("DELETE FROM outbox_events WHERE created_at < now() - interval '1 day'");
    }
}
