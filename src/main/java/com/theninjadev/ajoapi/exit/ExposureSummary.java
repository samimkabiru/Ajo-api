package com.theninjadev.ajoapi.exit;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record ExposureSummary(
        UUID participantId,
        @Schema(description = "collected + refunded + claim settlements − contributed − repaid. Positive means the member owes the group; negative means the group owes them; zero means square. Integer kobo: ₦10,000 is 1000000.", example = "-1000000")
        long exposureKobo,
        @Schema(description = "True when exposure is positive: they collected more than they paid in.")
        boolean owesGroup,
        @Schema(description = "True when exposure is negative: they paid in more than they have received.")
        boolean owedByGroup
) {}
