package com.theninjadev.ajoapi.group;

public class InviteNotFoundException extends RuntimeException {
    public InviteNotFoundException() {
        super("Invite not found");
    }
}
