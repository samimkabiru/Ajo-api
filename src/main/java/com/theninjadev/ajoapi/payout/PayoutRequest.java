package com.theninjadev.ajoapi.payout;

import jakarta.validation.constraints.NotNull;

public record PayoutRequest(@NotNull PayoutMethod method) {}
