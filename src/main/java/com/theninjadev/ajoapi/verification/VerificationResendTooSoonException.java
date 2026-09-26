package com.theninjadev.ajoapi.verification;

public class VerificationResendTooSoonException extends RuntimeException {
    public VerificationResendTooSoonException() {
        super("A code was just sent; wait a moment before requesting another");
    }
}
