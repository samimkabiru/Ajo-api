package com.theninjadev.ajoapi.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public static final String CLAIM_TOKEN_TYPE = "token-type";
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final SecretKey signingKey;

    /** Builds the signing key once, so a malformed or weak JWT_SECRET fails at startup. */
    public JwtService(JwtProperties jwtProperties, Clock clock) {
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        this.signingKey = signingKey(jwtProperties.secret());
    }

    public String generateAccessToken(UUID userId) {
        return generateToken(userId, TOKEN_TYPE_ACCESS, jwtProperties.accessTokenTtlMinutes(), ChronoUnit.MINUTES);
    }

    public String generateRefreshToken(UUID userId) {
        return generateToken(userId, TOKEN_TYPE_REFRESH, jwtProperties.refreshTokenTtlDays(), ChronoUnit.DAYS);
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
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
        Instant now = Instant.now(clock);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(amount, unit)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * JWT_SECRET is base64. JJWT picks HS256/HS384/HS512 from the decoded key length and
     * rejects anything under 256 bits.
     */
    private static SecretKey signingKey(String base64Secret) {
        try {
            return Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
        } catch (DecodingException e) {
            throw new IllegalStateException("JWT_SECRET is not valid base64", e);
        } catch (WeakKeyException e) {
            throw new IllegalStateException(
                    "JWT_SECRET is too short — it must decode to at least 256 bits (32 bytes)", e);
        }
    }
}
