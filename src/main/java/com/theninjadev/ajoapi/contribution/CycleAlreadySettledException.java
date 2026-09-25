package com.theninjadev.ajoapi.contribution;

public class CycleAlreadySettledException extends RuntimeException {
    public CycleAlreadySettledException() {
        super("This cycle has already been settled and no longer accepts contributions.");
    }
}
