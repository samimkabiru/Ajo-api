package com.theninjadev.ajoapi.contribution;

public class DuplicateContributionException extends RuntimeException {
    public DuplicateContributionException() {
        super("A contribution already exists for this participant and cycle");
    }
}
