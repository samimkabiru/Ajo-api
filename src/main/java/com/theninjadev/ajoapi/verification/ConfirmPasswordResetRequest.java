package com.theninjadev.ajoapi.verification;

import com.theninjadev.ajoapi.auth.MaxUtf8Bytes;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** newPassword carries exactly RegisterRequest.password's rules, including BCrypt's 72-byte limit. */
public record ConfirmPasswordResetRequest(
        @Schema(description = "The account's phone number, in any accepted Nigerian format.", example = "08031234567")
        @NotBlank String phone,
        @Schema(description = "The six-digit code sent by SMS.", example = "482913")
        @NotBlank @Pattern(regexp = "\\d{6}") String code,
        @Schema(description = "At least 8 characters and at most 72 bytes UTF-8 encoded.")
        @NotBlank @Size(min = 8) @MaxUtf8Bytes(72) String newPassword) {
}
