package com.theninjadev.ajoapi.round;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RoundSummary(
        UUID id,
        UUID groupId,
        long contributionAmountKobo,
        RoundStatus status,
        UUID createdBy,
        Instant activatedAt,
        LocalDate firstPayoutDate,
        Instant createdAt,
        Instant updatedAt
) {}
