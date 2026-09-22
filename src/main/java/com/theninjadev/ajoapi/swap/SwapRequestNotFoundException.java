package com.theninjadev.ajoapi.swap;

public class SwapRequestNotFoundException extends RuntimeException {
    public SwapRequestNotFoundException() {
        super("Swap request not found");
    }
}
