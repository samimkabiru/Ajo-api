package com.theninjadev.ajoapi.round;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RoundDetail(
        UUID id,
        UUID groupId,
        @Schema(description = "What each member pays every month. Integer kobo: ₦10,000 is 1000000.", example = "1000000")
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
