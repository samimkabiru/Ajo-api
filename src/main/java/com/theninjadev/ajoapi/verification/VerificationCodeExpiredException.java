package com.theninjadev.ajoapi.verification;

public class VerificationCodeExpiredException extends RuntimeException {
    public VerificationCodeExpiredException() {
        super("That code has expired; request a new one");
    }
}
