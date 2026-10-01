package com.theninjadev.ajoapi.group;

public class GroupHasFormingRoundException extends RuntimeException {
    public GroupHasFormingRoundException() {
        super("Cancel the round that's still forming before archiving this circle.");
    }
}
