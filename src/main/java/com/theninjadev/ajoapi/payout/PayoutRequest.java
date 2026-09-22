package com.theninjadev.ajoapi.payout;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PayoutRequest(
        @NotNull PayoutMethod method,
        @NotNull UUID expectedBeneficiaryUserId) {}
