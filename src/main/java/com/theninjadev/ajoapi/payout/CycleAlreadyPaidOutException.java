package com.theninjadev.ajoapi.payout;

public class CycleAlreadyPaidOutException extends RuntimeException {
    public CycleAlreadyPaidOutException() {
        super("This cycle has already been paid out");
    }
}
