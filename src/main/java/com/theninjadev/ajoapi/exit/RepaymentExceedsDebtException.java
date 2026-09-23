package com.theninjadev.ajoapi.exit;

public class RepaymentExceedsDebtException extends RuntimeException {
    public RepaymentExceedsDebtException() {
        super("This repayment is more than what is owed");
    }
}
