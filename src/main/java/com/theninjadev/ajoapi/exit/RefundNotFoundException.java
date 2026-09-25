package com.theninjadev.ajoapi.exit;

public class RefundNotFoundException extends RuntimeException {
    public RefundNotFoundException() {
        super("Refund not found");
    }
}
