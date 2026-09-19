package com.theninjadev.ajoapi.round;

public class InsufficientParticipantsException extends RuntimeException {
    public InsufficientParticipantsException() {
        super("Round needs at least 2 participants to activate");
    }
}
