package com.ims.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Opaque, rotating refresh tokens in Redis. Only the SHA-256 of a token is stored, so a Redis dump
 * yields nothing usable. Each refresh consumes the token; presenting a consumed token again means it
 * was copied, so every session of that user is revoked.
 */
@Service
class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final Duration ttl;

    RefreshTokenService(StringRedisTemplate redis, @Value("${ims.jwt.refresh-ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    String issue(UUID userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = hash(token);
        redis.opsForValue().set(activeKey(hash), userId.toString(), ttl);
        redis.opsForSet().add(sessionsKey(userId), hash);
        redis.expire(sessionsKey(userId), ttl);
        return token;
    }

    /** Consumes the token and returns its user; empty if invalid, expired or already used. */
    Optional<UUID> consume(String token) {
        String hash = hash(token);
        String userId = redis.opsForValue().getAndDelete(activeKey(hash));
        if (userId != null) {
            UUID id = UUID.fromString(userId);
            redis.opsForSet().remove(sessionsKey(id), hash);
            redis.opsForValue().set(usedKey(hash), userId, ttl);
            return Optional.of(id);
        }
        String reusedBy = redis.opsForValue().get(usedKey(hash));
        if (reusedBy != null) {
            log.warn("Refresh token reuse for user {}: revoking all sessions", reusedBy);
            revokeAll(UUID.fromString(reusedBy));
        }
        return Optional.empty();
    }

    void revoke(String token) {
        String hash = hash(token);
        String userId = redis.opsForValue().getAndDelete(activeKey(hash));
        if (userId != null) {
            redis.opsForSet().remove(sessionsKey(UUID.fromString(userId)), hash);
        }
    }

    void revokeAll(UUID userId) {
        Set<String> hashes = redis.opsForSet().members(sessionsKey(userId));
        if (hashes != null && !hashes.isEmpty()) {
            redis.delete(hashes.stream().map(RefreshTokenService::activeKey).toList());
        }
        redis.delete(sessionsKey(userId));
    }

    private static String activeKey(String hash) {
        return "refresh:" + hash;
    }

    private static String usedKey(String hash) {
        return "refresh-used:" + hash;
    }

    private static String sessionsKey(UUID userId) {
        return "user:" + userId + ":sessions";
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
