package com.theninjadev.ajoapi.verification;

public class TooManyVerificationAttemptsException extends RuntimeException {
    public TooManyVerificationAttemptsException() {
        super("Too many incorrect attempts; request a new code");
    }
}
