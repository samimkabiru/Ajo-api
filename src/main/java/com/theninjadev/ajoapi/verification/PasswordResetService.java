package com.theninjadev.ajoapi.verification;

import com.theninjadev.ajoapi.auth.PhoneNumberNormalizer;
import com.theninjadev.ajoapi.auth.RefreshTokenRepository;
import com.theninjadev.ajoapi.auth.User;
import com.theninjadev.ajoapi.auth.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Forgot password", for someone who cannot log in. Every response is a potential answer to
 * "does this number have an account here?", so the flow is built to give none:
 *  - request always returns the same 202 and body, and sends a code only when it truly should;
 *  - confirm has exactly one failure (PasswordResetFailedException), whatever went wrong;
 *  - every path runs exactly one BCrypt operation, so timing does not leak what the status hides.
 *
 * The code lifecycle is shared with phone verification through VerificationCodes.
 */
@Service
public class PasswordResetService {

    private static final VerificationPurpose PURPOSE = VerificationPurpose.PASSWORD_RESET;

    private final VerificationCodes verificationCodes;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final PasswordEncoder passwordEncoder;
    private final OtpProperties otpProperties;
    private final Clock clock;

    /** Compared against on paths that would otherwise skip BCrypt. Same encoder, so same cost. */
    private final String dummyHash;

    public PasswordResetService(VerificationCodes verificationCodes,
                                UserRepository userRepository,
                                RefreshTokenRepository refreshTokenRepository,
                                PhoneNumberNormalizer phoneNumberNormalizer,
                                PasswordEncoder passwordEncoder,
                                OtpProperties otpProperties,
                                Clock clock) {
        this.verificationCodes = verificationCodes;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.phoneNumberNormalizer = phoneNumberNormalizer;
        this.passwordEncoder = passwordEncoder;
        this.otpProperties = otpProperties;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Always the same answer. Deliberately not @Transactional: VerificationCodes.issue runs its
     * own transaction, so when it throws for a rate limit that transaction rolls back cleanly
     * and this method can still return normally.
     */
    public PasswordResetRequested request(String rawPhone) {
        String phone = phoneNumberNormalizer.normalize(rawPhone);   // format only: 400 reveals no account

        User user = userRepository.findByPhone(phone).orElse(null);
        if (user == null) {
            burnOneBcrypt();                                        // same work as issuing a code
        } else {
            try {
                verificationCodes.issue(user.getId(), user.getPhone(), PURPOSE, "Your Ajo password reset code is ");
            } catch (TooManyVerificationRequestsException | VerificationResendTooSoonException e) {
                // Rate-limited: silently send nothing. The check throws before hashing, so burn
                // the BCrypt here — otherwise a rate-limited real account answers faster than an
                // unknown number, which is exactly the fact the identical 202 exists to hide.
                burnOneBcrypt();
            }
        }

        return new PasswordResetRequested(otpProperties.ttl().toSeconds(), otpProperties.resendCooldown().toSeconds());
    }

    // noRollbackFor: a wrong code's attempt increment, and consuming an expired or exhausted code,
    // must persist even though the method then throws — otherwise the attempt cap never engages
    // and the code is brute-forceable (the same rollback trap as OtpService.verifyCode).
    @Transactional(noRollbackFor = PasswordResetFailedException.class)
    public void confirm(String rawPhone, String code, String newPassword) {
        String phone = phoneNumberNormalizer.normalize(rawPhone);

        User user = userRepository.findByPhone(phone).orElse(null);
        if (user == null) {
            burnOneBcrypt();
            throw new PasswordResetFailedException();
        }

        // Filters on PASSWORD_RESET: a phone-verification code can never reset a password.
        var result = verificationCodes.check(user.getId(), PURPOSE, code);
        if (result != VerificationCodes.CheckResult.VERIFIED) {
            // No live code, expired, or already exhausted return without comparing: burn one
            // BCrypt so every failure costs the same as a wrong code.
            if (!result.ranBcrypt)
                burnOneBcrypt();
            throw new PasswordResetFailedException();
        }

        Instant now = Instant.now(clock);
        user.changePassword(passwordEncoder.encode(newPassword), now);   // phone_verified untouched
        userRepository.save(user);

        // "I forgot my password" is sometimes "someone else has my account": end every session.
        var sessions = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(user.getId());
        sessions.forEach(token -> token.revoke(now));
        refreshTokenRepository.saveAll(sessions);
    }

    private void burnOneBcrypt() {
        passwordEncoder.matches("not-a-real-code", dummyHash);
    }
}
