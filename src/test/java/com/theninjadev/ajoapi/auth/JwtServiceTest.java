package com.theninjadev.ajoapi.auth;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.SignatureException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    // Dummy base64 keys, test-only. Each decodes to 320 bits: "test-only dummy JWT key #1/#2, not a secret".
    private static final String KEY_ONE = "dGVzdC1vbmx5IGR1bW15IEpXVCBrZXkgIzEsIG5vdCBhIHNlY3JldA==";
    private static final String KEY_TWO = "dGVzdC1vbmx5IGR1bW15IEpXVCBrZXkgIzIsIG5vdCBhIHNlY3JldA==";

    private final JwtProperties jwtProperties = new JwtProperties(KEY_ONE, 15, 30);

    @Test
    void accessTokenRoundTrips() {
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var jwtService = new JwtService(jwtProperties, clock);
        var userId = UUID.randomUUID();

        var token = jwtService.generateAccessToken(userId);
        var claims = jwtService.parseClaims(token);

        assertThat(jwtService.extractUserId(claims)).isEqualTo(userId);
        assertThat(jwtService.extractTokenType(claims)).isEqualTo(JwtService.TOKEN_TYPE_ACCESS);
    }

    @Test
    void refreshTokenIsMarkedWithRefreshType() {
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var jwtService = new JwtService(jwtProperties, clock);
        var userId = UUID.randomUUID();

        var token = jwtService.generateRefreshToken(userId);
        var claims = jwtService.parseClaims(token);

        assertThat(jwtService.extractTokenType(claims)).isEqualTo(JwtService.TOKEN_TYPE_REFRESH);
    }

    @Test
    void expiredAccessTokenFailsToParse() {
        var mintClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var mintingService = new JwtService(jwtProperties, mintClock);
        var token = mintingService.generateAccessToken(UUID.randomUUID());

        var laterClock = Clock.fixed(Instant.parse("2026-01-01T00:16:00Z"), ZoneOffset.UTC);
        var parsingService = new JwtService(jwtProperties, laterClock);

        assertThrows(ExpiredJwtException.class, () -> parsingService.parseClaims(token));
    }

    @Test
    void tokenSignedWithDifferentKeyFailsVerification() {
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var mintingService = new JwtService(jwtProperties, clock);
        var token = mintingService.generateAccessToken(UUID.randomUUID());

        var otherProperties = new JwtProperties(KEY_TWO, 15, 30);
        var verifyingService = new JwtService(otherProperties, clock);

        assertThrows(SignatureException.class, () -> verifyingService.parseClaims(token));
    }

    @Test
    void aMissingSecretIsRejectedAtStartup() {
        // An unset JWT_SECRET reaches the binder as the unresolved placeholder text.
        var e = assertThrows(IllegalStateException.class, () -> new JwtProperties("${JWT_SECRET}", 15, 30));
        assertThat(e.getMessage()).contains("JWT_SECRET is not set");
        assertThrows(IllegalStateException.class, () -> new JwtProperties(" ", 15, 30));
    }

    @Test
    void aKeyShorterThan256BitsIsRejectedAtStartup() {
        var clock = Clock.systemUTC();
        var weak = new JwtProperties("dG9vLXNob3J0LXRlc3Qta2V5", 15, 30);   // "too-short-test-key": 144 bits

        var e = assertThrows(IllegalStateException.class, () -> new JwtService(weak, clock));
        assertThat(e.getMessage()).contains("at least 256 bits");
    }

    @Test
    void malformedTokenThrows() {
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var jwtService = new JwtService(jwtProperties, clock);

        assertThrows(JwtException.class, () -> jwtService.parseClaims("not-a-jwt"));
    }
}
