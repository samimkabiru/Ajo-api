package com.theninjadev.ajoapi.contribution;

import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record ContributeRequest(
        @Positive long amountKobo,
        UUID userId,
        ContributionMethod method
) {}
