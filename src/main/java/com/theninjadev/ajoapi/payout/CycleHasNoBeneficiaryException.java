package com.theninjadev.ajoapi.payout;

public class CycleHasNoBeneficiaryException extends RuntimeException {
    public CycleHasNoBeneficiaryException() {
        super("This cycle has no beneficiary to pay out");
    }
}
