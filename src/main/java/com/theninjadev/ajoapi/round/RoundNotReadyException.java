package com.theninjadev.ajoapi.round;

public class RoundNotReadyException extends RuntimeException {
    public RoundNotReadyException() {
        super("A round cannot be activated without a first payout date.");
    }
}
