package com.theninjadev.ajoapi.verification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** One-time-code settings, bound from app.otp. None are secrets. */
@ConfigurationProperties(prefix = "app.otp")
public record OtpProperties(
        int codeLength,
        Duration ttl,
        int maxAttempts,
        int maxRequestsPerWindow,
        Duration requestWindow,
        Duration resendCooldown) {

    public OtpProperties {
        codeLength = codeLength > 0 ? codeLength : 6;
        ttl = ttl != null ? ttl : Duration.ofMinutes(10);
        maxAttempts = maxAttempts > 0 ? maxAttempts : 5;
        maxRequestsPerWindow = maxRequestsPerWindow > 0 ? maxRequestsPerWindow : 3;
        requestWindow = requestWindow != null ? requestWindow : Duration.ofHours(1);
        resendCooldown = resendCooldown != null ? resendCooldown : Duration.ofSeconds(60);

        // VerifyCodeRequest only accepts exactly six digits; any other length would issue codes
        // that can never be confirmed. Fail at startup instead.
        if (codeLength != 6)
            throw new IllegalStateException("app.otp.code-length must be 6 (VerifyCodeRequest accepts six digits)");
    }
}
