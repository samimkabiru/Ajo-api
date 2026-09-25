package com.theninjadev.ajoapi.payout;

import io.swagger.v3.oas.annotations.media.Schema;
import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record ShortfallClaimSummary(
        UUID id,
        UUID cycleId,
        UUID participantId,
        UserSummary participant,
        @Schema(description = "What the group owed this member when the claim was raised. Integer kobo: ₦10,000 is 1000000.", example = "1000000")
        long amountKobo,
        @Schema(description = "Paid so far, from vacant-cycle settlements or withheld arrears. Integer kobo: ₦10,000 is 1000000.", example = "0")
        long settledAmountKobo,
        @Schema(description = "amount − settled: still owed. Integer kobo: ₦10,000 is 1000000.", example = "1000000")
        long outstandingKobo,
        @Schema(description = "Set only when fully settled; null while open or part-paid.", nullable = true)
        Instant settledAt,
        Instant createdAt
) {}
