package com.theninjadev.ajoapi.round;

public class AlreadyRoundParticipantException extends RuntimeException {
    public AlreadyRoundParticipantException() {
        super("Already a participant in this round");
    }
}
