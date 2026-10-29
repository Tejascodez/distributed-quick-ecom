package com.ims.auth;

import java.time.Instant;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AuthController {

    record RegisterRequest(@NotBlank String fullName, @NotBlank String phone, String email,
                           @NotBlank @Size(min = 8) String password) {
    }

    record LoginRequest(@NotBlank String phone, @NotBlank String password) {
    }

    record RefreshRequest(@NotBlank String refreshToken) {
    }

    /** expiresIn: access token lifetime in seconds. */
    record TokenResponse(String accessToken, long expiresIn, String refreshToken) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final RSAKey signingKey;
    private final JwtProperties props;
    private final RefreshTokenService refreshTokens;

    AuthController(UserRepository users, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
                   RSAKey signingKey, JwtProperties props, RefreshTokenService refreshTokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.signingKey = signingKey;
        this.props = props;
        this.refreshTokens = refreshTokens;
    }

    @PostMapping("/api/v1/auth/register")
    @Transactional
    TokenResponse register(@Valid @RequestBody RegisterRequest req) {
        if (users.existsByPhone(req.phone())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone already registered");
        }
        User user = users.save(new User(req.fullName(), req.phone(), req.email(),
                passwordEncoder.encode(req.password()), "CUSTOMER"));
        return issue(user);
    }

    @PostMapping("/api/v1/auth/login")
    TokenResponse login(@Valid @RequestBody LoginRequest req) {
        User user = users.findByPhone(req.phone())
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        return issue(user);
    }

    /** Swaps a refresh token for a new pair (rotation). The role is re-read, so role changes apply here. */
    @PostMapping("/api/v1/auth/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return refreshTokens.consume(req.refreshToken())
                .flatMap(users::findById)
                .map(this::issue)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
    }

    /** Ends this session. The access token stays valid until it expires (at most ims.jwt.ttl). */
    @PostMapping("/api/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshRequest req) {
        refreshTokens.revoke(req.refreshToken());
    }

    /** Envoy's jwt_authn filter fetches this to validate tokens at the edge. */
    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }

    private TokenResponse issue(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.ttl()))
                .claim("role", user.getRole())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.getKeyID()).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, props.ttl().toSeconds(), refreshTokens.issue(user.getId()));
    }
}
