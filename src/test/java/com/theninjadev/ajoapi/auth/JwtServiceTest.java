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

    private final JwtProperties jwtProperties =
            new JwtProperties("0123456789abcdef0123456789abcdef01234567", 15, 30);

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

        var otherProperties = new JwtProperties("fedcba9876543210fedcba9876543210fedcba98", 15, 30);
        var verifyingService = new JwtService(otherProperties, clock);

        assertThrows(SignatureException.class, () -> verifyingService.parseClaims(token));
    }

    @Test
    void malformedTokenThrows() {
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var jwtService = new JwtService(jwtProperties, clock);

        assertThrows(JwtException.class, () -> jwtService.parseClaims("not-a-jwt"));
    }
}
