package com.theninjadev.ajoapi.round;

public class RoundNotFormingException extends RuntimeException {
    public RoundNotFormingException() {
        super("Round is not in FORMING state");
    }
}
