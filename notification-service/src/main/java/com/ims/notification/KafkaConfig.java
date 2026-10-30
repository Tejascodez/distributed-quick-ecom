package com.ims.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration
class KafkaConfig {

    /**
     * Retries transient failures a few times, then parks the record on {@code <topic>-dlt} instead of dropping it.
     * Malformed payloads and invalid events can never succeed, so they go straight to the DLT.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        var handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafka), new FixedBackOff(1_000, 3));
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class,
                IllegalStateException.class);
        return handler;
    }
}
