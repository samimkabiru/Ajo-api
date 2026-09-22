package com.theninjadev.ajoapi.swap;

public class NotSwapTargetException extends RuntimeException {
    public NotSwapTargetException() {
        super("Only the member this request was sent to can accept or decline it");
    }
}
