package com.ims.notification;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mobile push for when the app is backgrounded.
 * TODO: replace with Firebase Admin SDK (FirebaseMessaging) once service-account credentials exist.
 */
@Component
class PushSender {

    private static final Logger log = LoggerFactory.getLogger(PushSender.class);

    void send(UUID userId, String title, String body) {
        log.info("[push] user={} title='{}' body='{}'", userId, title, body);
    }
}
