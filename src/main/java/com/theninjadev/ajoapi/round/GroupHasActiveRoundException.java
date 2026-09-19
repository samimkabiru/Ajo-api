package com.theninjadev.ajoapi.round;

public class GroupHasActiveRoundException extends RuntimeException {
    public GroupHasActiveRoundException() {
        super("Group already has an active round");
    }
}
