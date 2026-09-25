package com.theninjadev.ajoapi.exit;

public class CycleAlreadySettledException extends RuntimeException {
    public CycleAlreadySettledException() {
        super("This cycle has already been fully settled");
    }
}
