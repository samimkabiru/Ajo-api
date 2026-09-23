package com.theninjadev.ajoapi.exit;

public class LeaverOwesGroupException extends RuntimeException {
    public LeaverOwesGroupException() {
        super("This member owes the group and their position cannot be bought into");
    }
}
