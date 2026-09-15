package com.theninjadev.ajoapi.ledger;

public class UnbalancedPostingException extends RuntimeException {
    public UnbalancedPostingException(String message) {
        super(message);
    }
}
