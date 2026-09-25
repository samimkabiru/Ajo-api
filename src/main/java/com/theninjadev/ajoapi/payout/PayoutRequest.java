package com.theninjadev.ajoapi.payout;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PayoutRequest(
        @NotNull PayoutMethod method,
        @Schema(description = "Who you believe is being paid. If the cycle's beneficiary has changed since you loaded it (for example after a swap), the payout is rejected with 409.")
        @NotNull UUID expectedBeneficiaryUserId) {}
