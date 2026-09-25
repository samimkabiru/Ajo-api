package com.theninjadev.ajoapi.exit;

import io.swagger.v3.oas.annotations.media.Schema;
import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record ExitRequestSummary(
        UUID id,
        UUID roundId,
        UUID participantId,
        UserSummary participant,
        @Schema(description = "Exposure when the exit was requested — an audit snapshot only; current exposure is always recomputed. Positive means they owed the group. Integer kobo: ₦10,000 is 1000000.", example = "-1000000")
        long exposureAtRequest,
        ExitStatus status,
        Instant requestedAt,
        @Schema(description = "When the exit completed or was cancelled; null while pending.", nullable = true)
        Instant completedAt
) {}
