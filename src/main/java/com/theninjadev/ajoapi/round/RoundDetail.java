package com.theninjadev.ajoapi.round;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RoundDetail(
        UUID id,
        UUID groupId,
        long contributionAmountKobo,
        RoundStatus status,
        UUID createdBy,
        Instant activatedAt,
        LocalDate firstPayoutDate,
        Instant createdAt,
        Instant updatedAt,
        List<ParticipantSummary> participants,
        List<CycleSummary> cycles
) {}
