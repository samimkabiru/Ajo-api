package com.theninjadev.ajoapi.round;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

public record UpdateRoundRequest(
        @Schema(description = "What each member pays every month. Integer kobo: ₦10,000 is 1000000.", example = "1000000")
        @Positive long contributionAmountKobo,
        @Schema(description = "Payout date of cycle 1; each later cycle pays one month on, clamped to the month's end.", example = "2026-03-31", nullable = true)
        LocalDate firstPayoutDate
) {}
