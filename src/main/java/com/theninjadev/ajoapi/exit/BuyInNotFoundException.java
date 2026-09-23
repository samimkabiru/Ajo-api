package com.theninjadev.ajoapi.exit;

public class BuyInNotFoundException extends RuntimeException {
    public BuyInNotFoundException() {
        super("Buy-in not found");
    }
}
