package com.theninjadev.ajoapi.verification;

public interface SmsSender {
    void send(String e164Phone, String message);
}
