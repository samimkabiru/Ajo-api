package com.theninjadev.ajoapi.contribution;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record ContributeRequest(
        @Schema(description = "Must equal the round's agreed monthly amount. Integer kobo: ₦10,000 is 1000000.", example = "1000000")
        @Positive long amountKobo,
        @Schema(description = "Optional. Omit to contribute for yourself; an admin may name another member to record a cash contribution on their behalf.", nullable = true)
        UUID userId,
        @Schema(description = "How the money was paid. Defaults to ONLINE when omitted.", nullable = true)
        ContributionMethod method
) {}
