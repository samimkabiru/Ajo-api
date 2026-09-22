package com.theninjadev.ajoapi.swap;

public class TargetAlreadyPaidOutException extends RuntimeException {
    public TargetAlreadyPaidOutException() {
        super("This member has already collected their payout in this round and can no longer swap positions");
    }
}
