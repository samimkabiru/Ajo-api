package com.theninjadev.ajoapi.swap;

public class OutgoingSwapAlreadyPendingException extends RuntimeException {
    public OutgoingSwapAlreadyPendingException() {
        super("You already have a pending swap request in this round; cancel it before making another");
    }
}
