package com.theninjadev.ajoapi.auth;

public record AuthResponse(String accessToken, UserSummary user) {
}
