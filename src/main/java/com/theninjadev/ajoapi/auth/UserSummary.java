package com.theninjadev.ajoapi.auth;

import java.util.UUID;

public record UserSummary(
        UUID id,
        String phone,
        String email,
        String fullName,
        boolean phoneVerified,
        boolean emailVerified
) {
}
