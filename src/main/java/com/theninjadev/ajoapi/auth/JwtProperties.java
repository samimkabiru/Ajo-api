package com.theninjadev.ajoapi.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings. The secret comes only from JWT_SECRET — there is no default — and must be a
 * base64-encoded key of at least 256 bits (JwtService decodes it and rejects weak keys).
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, long accessTokenTtlMinutes, long refreshTokenTtlDays) {

    public JwtProperties {
        // Spring leaves an unresolvable placeholder as the literal "${JWT_SECRET}" rather than
        // failing, so check for it here: a missing secret must stop startup, not the first login.
        if (secret == null || secret.isBlank() || secret.startsWith("${"))
            throw new IllegalStateException(
                    "JWT_SECRET is not set — provide a base64-encoded key of at least 256 bits "
                            + "(for example: openssl rand -base64 64)");
    }
}
