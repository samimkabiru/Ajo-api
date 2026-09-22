package com.theninjadev.ajoapi.swap;

public class CannotSwapWithSelfException extends RuntimeException {
    public CannotSwapWithSelfException() {
        super("You cannot swap positions with yourself");
    }
}
