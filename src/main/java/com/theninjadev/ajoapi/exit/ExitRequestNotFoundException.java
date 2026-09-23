package com.theninjadev.ajoapi.exit;

public class ExitRequestNotFoundException extends RuntimeException {
    public ExitRequestNotFoundException() {
        super("Exit request not found");
    }
}
