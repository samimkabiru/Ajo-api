package com.theninjadev.ajoapi.payout;

public class PayoutNotYetDueException extends RuntimeException {
    public PayoutNotYetDueException() {
        super("This cycle's payout date has not arrived yet");
    }
}
