package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record RepaymentSummary(
        UUID id,
        UUID participantId,
        UserSummary participant,
        long amountKobo,
        RepaymentMethod method,
        UUID recordedBy,
        UUID ledgerTransactionId,
        Instant createdAt
) {}
