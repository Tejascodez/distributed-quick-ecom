package com.ims.delivery;

import java.util.UUID;

import com.ims.common.events.DeliveryProgressEvent;
import com.ims.common.events.DeliveryProgressEvent.Stage;
import com.ims.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells order-service when a rider picks an order up and hands it over. A step is marked published once the
 * broker acknowledges it; steps that never were are resent by {@link #republishUnsent()}. order-service applies
 * each step once, in whatever order they arrive.
 */
@Component
class ProgressPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProgressPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    ProgressPublisher(KafkaTemplate<String, String> kafka, JdbcTemplate jdbc, JsonMapper json) {
        this.kafka = kafka;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Doesn't wait for the broker: the rider app's request shouldn't hang on Kafka. */
    void publish(UUID orderId, UUID partnerId, Stage stage) {
        String column = stage == Stage.PICKED_UP ? "picked_up_published_at" : "delivered_published_at";
        kafka.send(Topics.DELIVERY_PROGRESS, orderId.toString(),
                        json.writeValueAsString(new DeliveryProgressEvent(orderId, partnerId, stage)))
                .whenComplete((result, error) -> {
                    if (error == null) {
                        jdbc.update("UPDATE delivery_assignments SET " + column + " = now() WHERE order_id = ?", orderId);
                    } else {
                        log.warn("Could not publish {} for order {}; will retry", stage, orderId, error);
                    }
                });
    }

    /** Steps older than 10s that were never acknowledged. Cancelled deliveries are over: order-service ignores them. */
    @Scheduled(fixedDelayString = "PT5S")
    void republishUnsent() {
        jdbc.query("""
                SELECT order_id, partner_id,
                       picked_up_at IS NOT NULL AND picked_up_published_at IS NULL
                           AND picked_up_at < now() - interval '10 seconds',
                       delivered_at IS NOT NULL AND delivered_published_at IS NULL
                           AND delivered_at < now() - interval '10 seconds'
                FROM delivery_assignments
                WHERE cancelled_at IS NULL
                  AND ((picked_up_at IS NOT NULL AND picked_up_published_at IS NULL)
                    OR (delivered_at IS NOT NULL AND delivered_published_at IS NULL))
                LIMIT 100
                """, rs -> {
            UUID orderId = rs.getObject(1, UUID.class);
            UUID partnerId = rs.getObject(2, UUID.class);
            if (rs.getBoolean(3)) {
                publish(orderId, partnerId, Stage.PICKED_UP);
            }
            if (rs.getBoolean(4)) {
                publish(orderId, partnerId, Stage.DELIVERED);
            }
        });
    }
}
