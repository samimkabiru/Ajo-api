package com.theninjadev.ajoapi.exit;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * amountKobo is what the caller expects to pay. It is a confirmation, not an input:
 * the service rejects anything other than the leaver's actual contributed total.
 */
public record BuyInRequest(
        @NotNull UUID replacementUserId,
        @Positive long amountKobo,
        BuyInMethod method) {}
