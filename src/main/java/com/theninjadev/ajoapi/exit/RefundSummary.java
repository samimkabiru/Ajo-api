package com.theninjadev.ajoapi.exit;

import io.swagger.v3.oas.annotations.media.Schema;
import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record RefundSummary(
        UUID id,
        UUID cycleId,
        UUID exitRequestId,
        UUID participantId,
        @Schema(description = "The leaver, from the snapshot on the refund — they may no longer hold a slot in the round.")
        UserSummary recipient,
        @Schema(description = "What the leaver was owed. Integer kobo: ₦10,000 is 1000000.", example = "2000000")
        long expectedAmountKobo,
        @Schema(description = "What the vacant pot could pay them. Integer kobo: ₦10,000 is 1000000.", example = "2000000")
        long actualAmountKobo,
        @Schema(description = "expected − actual: still owed after this refund. Integer kobo: ₦10,000 is 1000000.", example = "0")
        long shortfallKobo,
        UUID ledgerTransactionId,
        Instant createdAt
) {}
