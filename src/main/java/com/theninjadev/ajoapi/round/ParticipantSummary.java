package com.theninjadev.ajoapi.round;

import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record ParticipantSummary(
        UUID id,
        UserSummary user,
        Integer payoutPosition,
        ParticipantStatus status,
        Instant joinedAt
) {}
