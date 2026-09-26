package com.theninjadev.ajoapi.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank String phone,
        @NotBlank @Size(min = 8) @MaxUtf8Bytes(72) String password,
        @NotBlank @Size(max = 100) String fullName,
        @Email @Size(max = 254) String email
) {}
