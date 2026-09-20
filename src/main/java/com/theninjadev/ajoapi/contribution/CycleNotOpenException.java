package com.theninjadev.ajoapi.contribution;

public class CycleNotOpenException extends RuntimeException {
    public CycleNotOpenException() {
        super("This cycle is not yet open for contributions");
    }
}
