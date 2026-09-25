package com.theninjadev.ajoapi.exit;

import java.util.UUID;

public record VacantCycleSummary(
        UUID cycleId,
        long potKobo,
        long refundOwedKobo,
        long openClaimsKobo,
        boolean readyToSettle
) {}
