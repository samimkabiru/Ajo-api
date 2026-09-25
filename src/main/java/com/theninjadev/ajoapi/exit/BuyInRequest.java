package com.theninjadev.ajoapi.exit;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * amountKobo is what the caller expects to pay. It is a confirmation, not an input:
 * the service rejects anything other than the leaver's actual contributed total.
 */
public record BuyInRequest(
        @Schema(description = "The group member taking over the slot. They must not already be in this round.")
        @NotNull UUID replacementUserId,
        @Schema(description = "Must exactly match the leaving member's contributed total — a confirmation of what you expect to pay, rejected with 400 if it differs. Integer kobo: ₦10,000 is 1000000.", example = "2000000")
        @Positive long amountKobo,
        @Schema(description = "How the replacement paid. Defaults to ONLINE when omitted.", nullable = true)
        BuyInMethod method) {}
