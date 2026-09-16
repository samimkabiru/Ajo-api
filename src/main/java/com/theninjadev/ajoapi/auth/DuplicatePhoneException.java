package com.theninjadev.ajoapi.auth;

public class DuplicatePhoneException extends RuntimeException {
    public DuplicatePhoneException() {
        super("Phone number is already registered");
    }
}
