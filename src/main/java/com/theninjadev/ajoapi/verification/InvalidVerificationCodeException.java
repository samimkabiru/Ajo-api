package com.theninjadev.ajoapi.verification;

public class InvalidVerificationCodeException extends RuntimeException {
    public InvalidVerificationCodeException() {
        super("That code is not correct");
    }
}
