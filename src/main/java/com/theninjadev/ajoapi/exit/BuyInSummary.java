package com.theninjadev.ajoapi.exit;

import io.swagger.v3.oas.annotations.media.Schema;
import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record BuyInSummary(
        UUID id,
        UUID roundId,
        UUID exitRequestId,
        @Schema(description = "The slot that changed hands. It now belongs to the replacement.")
        UUID participantId,
        @Schema(description = "Who left — from the snapshot on the buy-in, since the slot now belongs to the replacement.")
        UserSummary leaver,
        UserSummary replacement,
        @Schema(description = "Paid in by the replacement and refunded to the leaver. Integer kobo: ₦10,000 is 1000000.", example = "2000000")
        long amountKobo,
        BuyInMethod method,
        UUID recordedBy,
        @Schema(description = "Ledger transaction for the replacement's money coming in.")
        UUID buyInTransactionId,
        @Schema(description = "Ledger transaction for the leaver's refund going out.")
        UUID refundTransactionId,
        Instant createdAt
) {}
