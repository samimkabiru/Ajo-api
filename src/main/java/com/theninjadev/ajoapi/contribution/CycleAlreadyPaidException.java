package com.theninjadev.ajoapi.contribution;

public class CycleAlreadyPaidException extends RuntimeException {
    public CycleAlreadyPaidException() {
        super("This cycle has already been paid out");
    }
}
