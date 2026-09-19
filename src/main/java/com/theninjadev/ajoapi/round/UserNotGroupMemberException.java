package com.theninjadev.ajoapi.round;

public class UserNotGroupMemberException extends RuntimeException {
    public UserNotGroupMemberException() {
        super("That user is not a member of this group");
    }
}
