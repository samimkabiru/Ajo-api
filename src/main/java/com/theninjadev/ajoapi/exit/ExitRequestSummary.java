package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record ExitRequestSummary(
        UUID id,
        UUID roundId,
        UUID participantId,
        UserSummary participant,
        long exposureAtRequest,
        ExitStatus status,
        Instant requestedAt,
        Instant completedAt
) {}
