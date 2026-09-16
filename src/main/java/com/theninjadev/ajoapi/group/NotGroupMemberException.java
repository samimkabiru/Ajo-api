package com.theninjadev.ajoapi.group;

public class NotGroupMemberException extends RuntimeException {
    public NotGroupMemberException() {
        super("Not a member of this group");
    }
}
