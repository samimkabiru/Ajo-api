package com.theninjadev.ajoapi.contribution;

public class ParticipantNotInRoundException extends RuntimeException {
    public ParticipantNotInRoundException() {
        super("This participant does not belong to this round");
    }
}
