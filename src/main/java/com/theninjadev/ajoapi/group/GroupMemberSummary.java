package com.theninjadev.ajoapi.group;

import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Instant;

public record GroupMemberSummary(
        UserSummary user,
        GroupRole role,
        Instant joinedAt
) {}
