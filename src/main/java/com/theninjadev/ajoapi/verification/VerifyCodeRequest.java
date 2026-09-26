package com.theninjadev.ajoapi.verification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyCodeRequest(
        @Schema(description = "The six-digit code sent by SMS.", example = "482913")
        @NotBlank @Pattern(regexp = "\\d{6}") String code) {
}
