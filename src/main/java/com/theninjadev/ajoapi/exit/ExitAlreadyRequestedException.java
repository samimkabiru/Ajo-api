package com.theninjadev.ajoapi.exit;

public class ExitAlreadyRequestedException extends RuntimeException {
    public ExitAlreadyRequestedException() {
        super("You already have an exit request in progress for this round");
    }
}
