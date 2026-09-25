package com.theninjadev.ajoapi.exit;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record VacantCycleSummary(
        UUID cycleId,
        @Schema(description = "Everything this cycle has collected. Integer kobo: ₦10,000 is 1000000.", example = "2000000")
        long potKobo,
        @Schema(description = "What the leaver who vacated this cycle is still owed; zero once refunded. Integer kobo: ₦10,000 is 1000000.", example = "1000000")
        long refundOwedKobo,
        @Schema(description = "Outstanding across the round's open shortfall claims — what a settlement could pay after the refund. Integer kobo: ₦10,000 is 1000000.", example = "1000000")
        long openClaimsKobo,
        @Schema(description = "True when settlement can run now: the cycle is vacant (or its beneficiary is leaving), not paid out, its payout date has arrived, and there is money in the pot.")
        boolean readyToSettle
) {}
