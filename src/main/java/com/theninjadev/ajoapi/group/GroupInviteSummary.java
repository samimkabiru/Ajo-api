package com.theninjadev.ajoapi.group;

import java.time.Instant;
import java.util.UUID;

public record GroupInviteSummary(
        UUID id,
        UUID groupId,
        String phone,
        UUID invitedBy,
        InviteStatus status,
        Instant createdAt,
        Instant respondedAt
) {}
