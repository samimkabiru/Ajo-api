package com.theninjadev.ajoapi.contribution;

public class CycleNotFoundException extends RuntimeException {
    public CycleNotFoundException() {
        super("Cycle not found");
    }
}
