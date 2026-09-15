package com.theninjadev.ajoapi.ledger;

public class LedgerAccountNotFoundException extends RuntimeException {
    public LedgerAccountNotFoundException() {
        super("Ledger account not found");
    }
}
