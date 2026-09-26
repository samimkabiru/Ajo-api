package com.theninjadev.ajoapi.verification;

public class PhoneAlreadyVerifiedException extends RuntimeException {
    public PhoneAlreadyVerifiedException() {
        super("This phone number is already verified");
    }
}
