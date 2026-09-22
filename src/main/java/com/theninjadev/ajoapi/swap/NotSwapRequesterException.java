package com.theninjadev.ajoapi.swap;

public class NotSwapRequesterException extends RuntimeException {
    public NotSwapRequesterException() {
        super("Only the member who made this request can cancel it");
    }
}
