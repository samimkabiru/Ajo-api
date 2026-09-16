package com.theninjadev.ajoapi.auth;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid phone number or password");
    }
}
