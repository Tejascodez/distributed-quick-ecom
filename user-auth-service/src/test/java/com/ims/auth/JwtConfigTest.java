package com.ims.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class JwtConfigTest {

    private final JwtConfig config = new JwtConfig();

    static String newPkcs8Key() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
    }

    private static JwtProperties props(String key) {
        return new JwtProperties("ims-auth", Duration.ofMinutes(15), key);
    }

    @Test
    void loadsKeyAndDerivesPublicHalf() throws Exception {
        var key = config.signingKey(props(newPkcs8Key()));
        assertThat(key.isPrivate()).isTrue();
        assertThat(key.toPublicJWK().isPrivate()).isFalse();
        assertThat(key.toRSAPublicKey().getModulus()).isEqualTo(key.toRSAPrivateKey().getModulus());
    }

    @Test
    void keyIdIsStableForTheSameKey() throws Exception {
        String pem = newPkcs8Key();
        assertThat(config.signingKey(props(pem)).getKeyID()).isEqualTo(config.signingKey(props(pem)).getKeyID());
    }

    @Test
    void differentKeysGetDifferentKeyIds() throws Exception {
        assertThat(config.signingKey(props(newPkcs8Key())).getKeyID())
                .isNotEqualTo(config.signingKey(props(newPkcs8Key())).getKeyID());
    }

    @Test
    void missingKeyFailsFastWithHint() {
        assertThatThrownBy(() -> config.signingKey(props(" ")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_PRIVATE_KEY");
    }

    @Test
    void garbageKeyIsRejected() {
        assertThatThrownBy(() -> config.signingKey(props(Base64.getEncoder().encodeToString(new byte[64]))))
                .isInstanceOf(java.security.GeneralSecurityException.class);
    }
}
