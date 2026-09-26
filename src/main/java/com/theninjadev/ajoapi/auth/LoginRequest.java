package com.theninjadev.ajoapi.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String phone,
        @NotBlank @MaxUtf8Bytes(72) String password
) {
}
