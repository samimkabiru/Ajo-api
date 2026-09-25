package com.theninjadev.ajoapi.exit;

import io.swagger.v3.oas.annotations.media.Schema;
import com.theninjadev.ajoapi.payout.ShortfallClaimSummary;
import com.theninjadev.ajoapi.round.CycleStatus;
import java.util.List;
import java.util.UUID;

/** refund is null when an earlier settlement pass already paid it. */
public record SettlementSummary(
        UUID cycleId,
        @Schema(description = "Everything this vacant cycle has collected, before any settlement pass. Integer kobo: ₦10,000 is 1000000.", example = "2000000")
        long potKobo,
        @Schema(description = "The leaver's refund paid in this pass. Null when an earlier pass already paid it, or when nobody is owed a refund.", nullable = true)
        RefundSummary refund,
        @Schema(description = "Shortfall claims that received money in this pass, oldest first.")
        List<ShortfallClaimSummary> claimsSettled,
        @Schema(description = "What is left of the pot after this pass. It stays in the pool for a later pass. Integer kobo: ₦10,000 is 1000000.", example = "0")
        long remainingKobo,
        @Schema(description = "SETTLED when nothing is outstanding any more; otherwise VACANT, so a later pass can distribute more.")
        CycleStatus cycleStatus
) {}
