package com.theninjadev.ajoapi.round;

public class RoundAlreadyActivatedException extends RuntimeException {
    public RoundAlreadyActivatedException() {
        super("A round that has started cannot be deleted");
    }
}
