package com.theninjadev.ajoapi.group;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GroupDetail(
        UUID id,
        String name,
        String description,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        List<GroupMemberSummary> members
) {}
