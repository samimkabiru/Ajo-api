package com.theninjadev.ajoapi.payout;

public class NotCycleBeneficiaryException extends RuntimeException {
    public NotCycleBeneficiaryException() {
        super("Only the cycle's beneficiary or a group admin can record this payout");
    }
}
