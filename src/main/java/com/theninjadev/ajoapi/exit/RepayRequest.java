package com.theninjadev.ajoapi.exit;

import jakarta.validation.constraints.Positive;

public record RepayRequest(
        @Positive long amountKobo,
        RepaymentMethod method) {}
