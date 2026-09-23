package com.theninjadev.ajoapi.exit;

import java.util.UUID;

public record ExposureSummary(
        UUID participantId,
        long exposureKobo,
        boolean owesGroup,
        boolean owedByGroup
) {}
