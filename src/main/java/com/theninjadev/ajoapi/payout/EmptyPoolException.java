package com.theninjadev.ajoapi.payout;

public class EmptyPoolException extends RuntimeException {
    public EmptyPoolException() {
        super("There is nothing in the pool to pay out");
    }
}
