package com.theninjadev.ajoapi.round;

public class RoundNotFoundException extends RuntimeException {
    public RoundNotFoundException() {
        super("Round not found");
    }
}
