package com.theninjadev.ajoapi.group;

public class AlreadyGroupMemberException extends RuntimeException {
    public AlreadyGroupMemberException() {
        super("This phone number already belongs to a member of this group");
    }
}
