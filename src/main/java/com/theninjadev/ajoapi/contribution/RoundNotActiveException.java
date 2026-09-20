package com.theninjadev.ajoapi.contribution;

public class RoundNotActiveException extends RuntimeException {
    public RoundNotActiveException() {
        super("This round is not active");
    }
}
