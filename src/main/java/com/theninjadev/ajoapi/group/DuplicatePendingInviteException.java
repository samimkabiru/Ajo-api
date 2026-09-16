package com.theninjadev.ajoapi.group;

public class DuplicatePendingInviteException extends RuntimeException {
    public DuplicatePendingInviteException() {
        super("A pending invite already exists for this phone number");
    }
}
