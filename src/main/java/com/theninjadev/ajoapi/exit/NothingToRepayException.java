package com.theninjadev.ajoapi.exit;

public class NothingToRepayException extends RuntimeException {
    public NothingToRepayException() {
        super("You do not owe anything in this round");
    }
}
