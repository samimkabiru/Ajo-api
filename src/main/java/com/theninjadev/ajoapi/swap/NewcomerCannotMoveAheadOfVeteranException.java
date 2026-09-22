package com.theninjadev.ajoapi.swap;

public class NewcomerCannotMoveAheadOfVeteranException extends RuntimeException {
    public NewcomerCannotMoveAheadOfVeteranException() {
        super("Members in their first round cannot move ahead of members who have completed a round");
    }
}
