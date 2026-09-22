package com.theninjadev.ajoapi.swap;

public class ParticipantNotActiveException extends RuntimeException {
    public ParticipantNotActiveException() {
        super("This member is no longer active in the round");
    }
}
