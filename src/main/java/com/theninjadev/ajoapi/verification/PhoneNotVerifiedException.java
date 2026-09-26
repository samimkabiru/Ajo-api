package com.theninjadev.ajoapi.verification;

public class PhoneNotVerifiedException extends RuntimeException {
    public PhoneNotVerifiedException() {
        super("Verify your phone number before creating or joining a group");
    }
}
