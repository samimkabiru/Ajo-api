package com.theninjadev.ajoapi.verification;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The configured constants, identical for every caller — enough for a countdown, and nothing
 * that could reveal whether the number has an account. Never a per-user expiresAt.
 */
public record PasswordResetRequested(
        @Schema(description = "How long a code stays valid, in seconds.", example = "600") long ttlSeconds,
        @Schema(description = "How long to wait before requesting another code, in seconds.", example = "60")
        long resendCooldownSeconds) {
}
