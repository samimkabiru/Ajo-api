package com.theninjadev.ajoapi.exit;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

public record RepayRequest(
        @Schema(description = "How much to repay now. Partial repayments are fine; more than is owed is rejected. Integer kobo: ₦10,000 is 1000000.", example = "500000")
        @Positive long amountKobo,
        @Schema(description = "How the money was paid. Defaults to ONLINE when omitted.", nullable = true)
        RepaymentMethod method) {}
