package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record RefundSummary(
        UUID id,
        UUID cycleId,
        UUID exitRequestId,
        UUID participantId,
        UserSummary recipient,
        long expectedAmountKobo,
        long actualAmountKobo,
        long shortfallKobo,
        UUID ledgerTransactionId,
        Instant createdAt
) {}
