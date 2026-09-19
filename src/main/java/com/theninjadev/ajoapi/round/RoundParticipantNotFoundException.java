package com.theninjadev.ajoapi.round;

public class RoundParticipantNotFoundException extends RuntimeException {
    public RoundParticipantNotFoundException() {
        super("Not a participant in this round");
    }
}
