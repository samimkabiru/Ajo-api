package com.theninjadev.ajoapi.auth;

import lombok.Getter;

/**
 * Login refused because the phone number is blocked. retryAfterSeconds is always the configured
 * block duration — never the time remaining, which would vary per number and leak when the
 * block started.
 */
@Getter
public class LoginRateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public LoginRateLimitedException(long retryAfterSeconds) {
        super("Too many failed login attempts; try again later");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
