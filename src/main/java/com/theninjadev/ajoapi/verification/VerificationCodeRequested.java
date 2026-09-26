package com.theninjadev.ajoapi.verification;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** What a client needs to show an expiry countdown and a disabled resend button. Never the code. */
public record VerificationCodeRequested(
        @Schema(description = "When the code stops working.") Instant expiresAt,
        @Schema(description = "The earliest moment another code may be requested.") Instant resendAvailableAt) {
}
