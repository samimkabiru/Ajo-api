package com.theninjadev.ajoapi.auth;

public class InvalidPhoneNumberException extends RuntimeException {
    public InvalidPhoneNumberException() {
        super("Invalid Nigerian phone number");
    }
}
