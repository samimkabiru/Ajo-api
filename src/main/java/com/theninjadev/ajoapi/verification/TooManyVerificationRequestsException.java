package com.theninjadev.ajoapi.verification;

public class TooManyVerificationRequestsException extends RuntimeException {
    public TooManyVerificationRequestsException() {
        super("Too many verification codes requested; try again later");
    }
}
