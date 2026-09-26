package com.theninjadev.ajoapi.verification;

/**
 * The only failure a password-reset confirm ever reports. No such user, no live code, wrong
 * code, expired code, attempts exhausted, wrong purpose — all of them are this, deliberately:
 * each distinguishable outcome would be another bit of information about who has an account.
 */
public class PasswordResetFailedException extends RuntimeException {
    public PasswordResetFailedException() {
        super("That reset code is not valid");
    }
}
