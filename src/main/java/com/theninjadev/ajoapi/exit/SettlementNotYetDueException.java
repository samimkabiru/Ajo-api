package com.theninjadev.ajoapi.exit;

public class SettlementNotYetDueException extends RuntimeException {
    public SettlementNotYetDueException() {
        super("This cycle's payout date has not arrived yet");
    }
}
