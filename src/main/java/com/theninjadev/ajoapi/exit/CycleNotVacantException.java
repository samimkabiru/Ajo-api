package com.theninjadev.ajoapi.exit;

public class CycleNotVacantException extends RuntimeException {
    public CycleNotVacantException() {
        super("Only a vacant cycle can be settled");
    }
}
