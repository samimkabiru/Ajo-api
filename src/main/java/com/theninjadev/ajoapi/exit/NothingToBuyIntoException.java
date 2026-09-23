package com.theninjadev.ajoapi.exit;

public class NothingToBuyIntoException extends RuntimeException {
    public NothingToBuyIntoException() {
        super("This member has already collected their payout, so there is no position to take over");
    }
}
