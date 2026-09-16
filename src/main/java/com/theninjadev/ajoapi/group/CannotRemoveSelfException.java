package com.theninjadev.ajoapi.group;

public class CannotRemoveSelfException extends RuntimeException {
    public CannotRemoveSelfException() {
        super("Admins cannot remove themselves; use leave instead");
    }
}
