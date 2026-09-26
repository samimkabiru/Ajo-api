package com.theninjadev.ajoapi.verification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(
        @Schema(description = "The account's phone number, in any accepted Nigerian format.", example = "08031234567")
        @NotBlank String phone) {
}
