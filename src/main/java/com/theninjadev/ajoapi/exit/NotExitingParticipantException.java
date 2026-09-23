package com.theninjadev.ajoapi.exit;

public class NotExitingParticipantException extends RuntimeException {
    public NotExitingParticipantException() {
        super("Only the member who requested this exit can cancel it");
    }
}
