package com.theninjadev.ajoapi.swap;

public class SwapRequestNotPendingException extends RuntimeException {
    public SwapRequestNotPendingException() {
        super("This swap request has already been resolved");
    }
}
