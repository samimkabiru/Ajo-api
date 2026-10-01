package com.theninjadev.ajoapi.group;

import java.time.Instant;
import java.util.UUID;

public record GroupSummary(
        UUID id,
        String name,
        String description,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt
) {}
