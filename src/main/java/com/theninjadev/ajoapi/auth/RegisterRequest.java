package com.theninjadev.ajoapi.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank String phone,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank String fullName,
        @Email String email
) {}
