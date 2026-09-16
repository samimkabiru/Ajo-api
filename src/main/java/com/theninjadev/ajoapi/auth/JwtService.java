package com.theninjadev.ajoapi.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class JwtService {

    public static final String CLAIM_TOKEN_TYPE = "token-type";
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    private final JwtProperties jwtProperties;
    private final Clock clock;

    public String generateAccessToken(UUID userId) {
        return generateToken(userId, TOKEN_TYPE_ACCESS, jwtProperties.accessTokenTtlMinutes(), ChronoUnit.MINUTES);
    }

    public String generateRefreshToken(UUID userId) {
        return generateToken(userId, TOKEN_TYPE_REFRESH, jwtProperties.refreshTokenTtlDays(), ChronoUnit.DAYS);
    }

    public Claims parseClaims(String token) {
        SecretKey key = signingKey();
        return Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(Instant.now(clock)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractTokenType(Claims claims) {
        return claims.get(CLAIM_TOKEN_TYPE, String.class);
    }

    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    private String generateToken(UUID userId, String tokenType, long amount, ChronoUnit unit) {
        SecretKey key = signingKey();
        Instant now = Instant.now(clock);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(amount, unit)))
                .signWith(key)
                .compact();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }
}
