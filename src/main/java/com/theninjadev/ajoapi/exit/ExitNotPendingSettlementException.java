package com.theninjadev.ajoapi.exit;

public class ExitNotPendingSettlementException extends RuntimeException {
    public ExitNotPendingSettlementException() {
        super("This exit request has already been resolved");
    }
}
