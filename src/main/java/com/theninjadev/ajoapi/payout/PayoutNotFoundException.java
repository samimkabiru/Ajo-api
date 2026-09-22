package com.theninjadev.ajoapi.payout;

public class PayoutNotFoundException extends RuntimeException {
    public PayoutNotFoundException() {
        super("Payout not found");
    }
}
