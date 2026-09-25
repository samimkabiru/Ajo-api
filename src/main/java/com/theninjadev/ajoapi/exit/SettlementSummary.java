package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.payout.ShortfallClaimSummary;
import com.theninjadev.ajoapi.round.CycleStatus;
import java.util.List;
import java.util.UUID;

/** refund is null when an earlier settlement pass already paid it. */
public record SettlementSummary(
        UUID cycleId,
        long potKobo,
        RefundSummary refund,
        List<ShortfallClaimSummary> claimsSettled,
        long remainingKobo,
        CycleStatus cycleStatus
) {}
