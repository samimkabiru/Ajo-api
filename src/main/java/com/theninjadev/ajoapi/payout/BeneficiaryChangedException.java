package com.theninjadev.ajoapi.payout;

public class BeneficiaryChangedException extends RuntimeException {
    public BeneficiaryChangedException() {
        super("This cycle's beneficiary changed since you loaded it — reload and try again");
    }
}
