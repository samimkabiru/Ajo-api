package com.theninjadev.ajoapi.payout;

import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record ShortfallClaimSummary(
        UUID id,
        UUID cycleId,
        UUID participantId,
        UserSummary participant,
        long amountKobo,
        Instant settledAt,
        Instant createdAt
) {}
