package com.theninjadev.ajoapi.exit;

public class NothingToSettleException extends RuntimeException {
    public NothingToSettleException() {
        super("There is nothing left to distribute for this cycle");
    }
}
