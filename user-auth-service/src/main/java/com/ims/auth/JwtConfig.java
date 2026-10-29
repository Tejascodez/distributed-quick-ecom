package com.ims.auth;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
class JwtConfig {

    /**
     * Signing key from JWT_PRIVATE_KEY. Its kid is the RFC 7638 thumbprint, so it is stable across restarts
     * and Envoy's cached JWKS stays valid. To rotate: publish the new key alongside the old in the JWKS first.
     */
    @Bean
    RSAKey signingKey(JwtProperties props) throws GeneralSecurityException, JOSEException {
        if (props.privateKey() == null || props.privateKey().isBlank()) {
            throw new IllegalStateException("JWT_PRIVATE_KEY is not set (see .env.example)");
        }
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        var privateKey = (RSAPrivateCrtKey) rsa.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(props.privateKey())));
        var publicKey = (RSAPublicKey) rsa.generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
