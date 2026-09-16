package com.theninjadev.ajoapi.group;

public class InviteNotPendingException extends RuntimeException {
    public InviteNotPendingException() {
        super("This invite has already been responded to");
    }
}
