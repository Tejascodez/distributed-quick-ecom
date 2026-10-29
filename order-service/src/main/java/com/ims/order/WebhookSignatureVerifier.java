package com.ims.order;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies payment gateway callbacks: signature = hex(HMAC-SHA256(secret, timestamp + "." + rawBody)).
 * Signing the timestamp lets us reject replays of captured requests outside the tolerance window.
 */
@Component
class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;
    private final Duration tolerance;
    private final Clock clock;

    @Autowired
    WebhookSignatureVerifier(@Value("${ims.payments.webhook-secret}") String secret,
                             @Value("${ims.payments.webhook-tolerance}") Duration tolerance) {
        this(secret, tolerance, Clock.systemUTC());
    }

    WebhookSignatureVerifier(String secret, Duration tolerance, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("ims.payments.webhook-secret must be set");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.tolerance = tolerance;
        this.clock = clock;
    }

    boolean isValid(String timestamp, String signature, byte[] body) {
        if (timestamp == null || signature == null) {
            return false;
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        Duration age = Duration.between(Instant.ofEpochSecond(epochSeconds), clock.instant()).abs();
        if (age.compareTo(tolerance) > 0) {
            return false;
        }
        byte[] expected = sign(timestamp, body);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided); // constant-time
    }

    byte[] sign(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
