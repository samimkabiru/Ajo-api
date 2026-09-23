package com.theninjadev.ajoapi.payout;

public class BeneficiaryNotActiveException extends RuntimeException {
    public BeneficiaryNotActiveException() {
        super("This cycle's beneficiary is leaving the round and cannot be paid out.");
    }
}
