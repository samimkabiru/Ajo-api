package com.theninjadev.ajoapi.contribution;

import io.swagger.v3.oas.annotations.media.Schema;
import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record ContributionSummary(
        UUID id,
        UUID cycleId,
        UUID participantId,
        UserSummary participant,
        @Schema(description = "The contribution. Integer kobo: ₦10,000 is 1000000.", example = "1000000")
        long amountKobo,
        ContributionMethod method,
        UUID recordedBy,
        UUID ledgerTransactionId,
        Instant createdAt
) {}
