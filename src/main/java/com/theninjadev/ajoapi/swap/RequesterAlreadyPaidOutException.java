package com.theninjadev.ajoapi.swap;

public class RequesterAlreadyPaidOutException extends RuntimeException {
    public RequesterAlreadyPaidOutException() {
        super("You have already collected your payout in this round and can no longer swap positions");
    }
}
