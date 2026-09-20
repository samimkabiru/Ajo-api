package com.theninjadev.ajoapi.contribution;

import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record ContributionSummary(
        UUID id,
        UUID cycleId,
        UUID participantId,
        UserSummary participant,
        long amountKobo,
        ContributionMethod method,
        UUID recordedBy,
        UUID ledgerTransactionId,
        Instant createdAt
) {}
