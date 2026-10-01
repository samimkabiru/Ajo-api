package com.theninjadev.ajoapi.group;

import java.time.Instant;
import java.util.UUID;

public record GroupInviteSummary(
        UUID id,
        UUID groupId,
        String phone,
        UUID invitedBy,
        // A name, not a UserSummary: the invitee isn't a member yet, so the inviter's phone and email aren't theirs to see.
        String inviterName,
        InviteStatus status,
        Instant createdAt,
        Instant respondedAt
) {}
