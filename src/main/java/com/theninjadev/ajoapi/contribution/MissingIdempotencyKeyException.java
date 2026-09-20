package com.theninjadev.ajoapi.contribution;

public class MissingIdempotencyKeyException extends RuntimeException {
    public MissingIdempotencyKeyException() {
        super("An Idempotency-Key header is required");
    }
}
