package com.theninjadev.ajoapi.group;

public class GroupArchivedException extends RuntimeException {
    public GroupArchivedException() {
        super("This circle is archived and can no longer be changed");
    }
}
