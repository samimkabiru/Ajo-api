package com.theninjadev.ajoapi.ledger;

public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException() {
        super("This idempotency key was already used for a different request.");
    }
}
