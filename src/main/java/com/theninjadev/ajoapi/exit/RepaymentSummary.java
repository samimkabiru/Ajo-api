package com.theninjadev.ajoapi.exit;

import io.swagger.v3.oas.annotations.media.Schema;
import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record RepaymentSummary(
        UUID id,
        UUID participantId,
        UserSummary participant,
        @Schema(description = "Repaid by a member who had collected more than they contributed. Integer kobo: ₦10,000 is 1000000.", example = "500000")
        long amountKobo,
        RepaymentMethod method,
        UUID recordedBy,
        UUID ledgerTransactionId,
        Instant createdAt
) {}
