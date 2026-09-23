package com.theninjadev.ajoapi.exit;

public class ExitAlreadySettledException extends RuntimeException {
    public ExitAlreadySettledException() {
        super("This exit has already been settled");
    }
}
