package com.ims.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ims.jwt")
/**
 * @param privateKey base64 PKCS#8 DER RSA key (one line, so it fits in .env).
 *                   Generate: openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 | openssl pkcs8 -topk8 -nocrypt -outform DER | base64
 */
public record JwtProperties(String issuer, Duration ttl, String privateKey) {
}
