package com.theninjadev.ajoapi.exit;

public class BuyInAmountMismatchException extends RuntimeException {
    public BuyInAmountMismatchException() {
        super("The buy-in must match exactly what the leaving member contributed");
    }
}
