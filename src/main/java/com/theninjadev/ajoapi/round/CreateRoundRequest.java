package com.theninjadev.ajoapi.round;

import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

public record CreateRoundRequest(
        @Positive long contributionAmountKobo,
        LocalDate firstPayoutDate
) {}
