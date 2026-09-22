package com.theninjadev.ajoapi.swap;

import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record SwapRequestSummary(
        UUID id,
        UUID roundId,
        UUID requesterParticipantId,
        UserSummary requester,
        UUID targetParticipantId,
        UserSummary target,
        int requesterPosition,
        int targetPosition,
        SwapStatus status,
        Instant createdAt,
        Instant respondedAt
) {}
