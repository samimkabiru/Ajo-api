package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record BuyInSummary(
        UUID id,
        UUID roundId,
        UUID exitRequestId,
        UUID participantId,
        UserSummary leaver,
        UserSummary replacement,
        long amountKobo,
        BuyInMethod method,
        UUID recordedBy,
        UUID buyInTransactionId,
        UUID refundTransactionId,
        Instant createdAt
) {}
