package com.theninjadev.ajoapi.verification;

public class NoActiveVerificationCodeException extends RuntimeException {
    public NoActiveVerificationCodeException() {
        super("No verification code has been requested, or it is no longer valid");
    }
}
