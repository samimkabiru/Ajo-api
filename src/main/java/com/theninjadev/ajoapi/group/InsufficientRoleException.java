package com.theninjadev.ajoapi.group;

public class InsufficientRoleException extends RuntimeException {
    public InsufficientRoleException() {
        super("This action requires the ADMIN role");
    }
}
