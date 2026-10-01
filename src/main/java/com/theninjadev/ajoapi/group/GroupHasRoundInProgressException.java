package com.theninjadev.ajoapi.group;

public class GroupHasRoundInProgressException extends RuntimeException {
    public GroupHasRoundInProgressException() {
        super("This circle has a round in progress and cannot be removed");
    }
}
