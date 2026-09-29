package com.theninjadev.ajoapi.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Login rate limiting, bound from app.security.login-rate-limit. Deliberately no "enabled"
 * flag: a switch to turn this off is a switch that ends up off in production.
 */
@ConfigurationProperties(prefix = "app.security.login-rate-limit")
public record LoginRateLimitProperties(int maxAttempts, Duration window, Duration blockDuration) {

    public LoginRateLimitProperties {
        maxAttempts = maxAttempts > 0 ? maxAttempts : 5;
        window = window != null ? window : Duration.ofMinutes(15);
        blockDuration = blockDuration != null ? blockDuration : Duration.ofMinutes(15);
    }
}
