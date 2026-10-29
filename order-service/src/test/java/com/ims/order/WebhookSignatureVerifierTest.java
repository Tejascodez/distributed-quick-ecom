package com.ims.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static final byte[] BODY = "{\"orderId\":\"6f1c\",\"success\":true}".getBytes(StandardCharsets.UTF_8);

    private final WebhookSignatureVerifier verifier =
            new WebhookSignatureVerifier("secret", Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

    private String sign(String timestamp, byte[] body) {
        return HexFormat.of().formatHex(verifier.sign(timestamp, body));
    }

    @Test
    void acceptsValidSignature() {
        String ts = String.valueOf(NOW.getEpochSecond());
        assertThat(verifier.isValid(ts, sign(ts, BODY), BODY)).isTrue();
    }

    @Test
    void rejectsTamperedBody() {
        String ts = String.valueOf(NOW.getEpochSecond());
        byte[] tampered = "{\"orderId\":\"6f1c\",\"success\":false}".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.isValid(ts, sign(ts, BODY), tampered)).isFalse();
    }

    @Test
    void rejectsSignatureFromDifferentSecret() {
        String ts = String.valueOf(NOW.getEpochSecond());
        var other = new WebhookSignatureVerifier("other", Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(verifier.isValid(ts, HexFormat.of().formatHex(other.sign(ts, BODY)), BODY)).isFalse();
    }

    @Test
    void rejectsReplayOutsideTolerance() {
        String old = String.valueOf(NOW.minus(Duration.ofMinutes(6)).getEpochSecond());
        assertThat(verifier.isValid(old, sign(old, BODY), BODY)).isFalse();
    }

    @Test
    void rejectsMissingOrMalformedHeaders() {
        String ts = String.valueOf(NOW.getEpochSecond());
        assertThat(verifier.isValid(null, sign(ts, BODY), BODY)).isFalse();
        assertThat(verifier.isValid(ts, null, BODY)).isFalse();
        assertThat(verifier.isValid("yesterday", sign(ts, BODY), BODY)).isFalse();
        assertThat(verifier.isValid(ts, "not-hex", BODY)).isFalse();
    }

    @Test
    void refusesBlankSecret() {
        assertThatThrownBy(() -> new WebhookSignatureVerifier(" ", Duration.ofMinutes(5), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }
}
