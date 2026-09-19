package com.theninjadev.ajoapi.round;

import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

public record UpdateRoundRequest(
        @Positive long contributionAmountKobo,
        LocalDate firstPayoutDate
) {}
