package com.theninjadev.ajoapi.swap;

public class SwapRequestStaleException extends RuntimeException {
    public SwapRequestStaleException() {
        super("This swap request is no longer valid because a payout position changed");
    }
}
