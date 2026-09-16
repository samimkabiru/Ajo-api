package com.theninjadev.ajoapi.group;

public class GroupNotFoundException extends RuntimeException {
    public GroupNotFoundException() {
        super("Group not found");
    }
}
