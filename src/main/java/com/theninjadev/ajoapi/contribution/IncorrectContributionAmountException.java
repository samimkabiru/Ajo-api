package com.theninjadev.ajoapi.contribution;

public class IncorrectContributionAmountException extends RuntimeException {
    public IncorrectContributionAmountException() {
        super("The contribution must match the round's agreed amount");
    }
}
