package com.theninjadev.ajoapi.group;

public class CannotRemoveLastAdminException extends RuntimeException {
    public CannotRemoveLastAdminException() {
        super("The last remaining admin cannot be removed or leave the group");
    }
}
