package com.theninjadev.ajoapi.payout;

import io.swagger.v3.oas.annotations.media.Schema;
import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record PayoutSummary(
        UUID id,
        UUID cycleId,
        UUID participantId,
        UserSummary beneficiary,
        @Schema(description = "The full pot: participants × the round's monthly amount. Integer kobo: ₦10,000 is 1000000.", example = "3000000")
        long expectedAmountKobo,
        @Schema(description = "What the beneficiary actually received: the pool balance capped at the full pot, minus arrears withheld. Can be zero. Integer kobo: ₦10,000 is 1000000.", example = "2000000")
        long actualAmountKobo,
        @Schema(description = "The shortfall claim raised because other members underpaid this cycle — what the group still owes the beneficiary. Never includes arrears withheld or the beneficiary's own missing share. Integer kobo: ₦10,000 is 1000000.", example = "0")
        long shortfallKobo,
        @Schema(description = "The beneficiary's own missed contributions to earlier cycles, held back from this payout and used to settle the claims they caused. Integer kobo: ₦10,000 is 1000000.", example = "1000000")
        long arrearsWithheldKobo,
        PayoutMethod method,
        UUID recordedBy,
        @Schema(description = "Ledger transaction for the payout. Null when the whole payout was withheld for arrears, since nothing was paid.", nullable = true)
        UUID ledgerTransactionId,
        Instant createdAt
) {}
