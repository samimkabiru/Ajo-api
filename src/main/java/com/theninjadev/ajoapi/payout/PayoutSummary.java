package com.theninjadev.ajoapi.payout;

import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record PayoutSummary(
        UUID id,
        UUID cycleId,
        UUID participantId,
        UserSummary beneficiary,
        long expectedAmountKobo,
        long actualAmountKobo,
        long shortfallKobo,
        long arrearsWithheldKobo,
        PayoutMethod method,
        UUID recordedBy,
        UUID ledgerTransactionId,
        Instant createdAt
) {}
