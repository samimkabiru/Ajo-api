package com.theninjadev.ajoapi.verification;

import com.theninjadev.ajoapi.auth.User;
import com.theninjadev.ajoapi.auth.UserMapper;
import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phone verification for the signed-in user. The code lifecycle itself — issuing, hashing,
 * rate limits, attempt cap — lives in VerificationCodes, shared with password reset.
 */
@Service
@AllArgsConstructor
public class OtpService {

    private static final VerificationPurpose PURPOSE = VerificationPurpose.PHONE_VERIFICATION;

    private final VerificationCodes verificationCodes;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final Clock clock;

    @Transactional
    public VerificationCodeRequested requestCode(UUID callerId) {
        User user = userOrUnauthenticated(callerId);
        if (user.isPhoneVerified())
            throw new PhoneAlreadyVerifiedException();

        // Rate-limit breaches surface as TooManyVerificationRequests / VerificationResendTooSoon (429).
        var issued = verificationCodes.issue(callerId, user.getPhone(), PURPOSE, "Your Ajo verification code is ");
        return new VerificationCodeRequested(issued.expiresAt(), issued.resendAvailableAt());
    }

    // A @Transactional method that throws rolls back — so written naively, the failed-attempt
    // increment is undone by the very exception that reports it, the attempt cap never engages,
    // and a six-digit code is brute-forceable. The same goes for consuming an expired or exhausted
    // code. These three exceptions report a state change that must persist, so they commit.
    // Deliberately narrow: each of those paths writes exactly one thing, and every other exception
    // rolls back as normal. Not REQUIRES_NEW: calling a REQUIRES_NEW method on `this` bypasses the
    // Spring proxy and silently does nothing — worse, because it looks correct.
    @Transactional(noRollbackFor = {
            InvalidVerificationCodeException.class,
            VerificationCodeExpiredException.class,
            TooManyVerificationAttemptsException.class })
    public UserSummary verifyCode(UUID callerId, String submittedCode) {
        User user = userOrUnauthenticated(callerId);
        if (user.isPhoneVerified())
            throw new PhoneAlreadyVerifiedException();

        switch (verificationCodes.check(callerId, PURPOSE, submittedCode)) {
            case NO_LIVE_CODE -> throw new NoActiveVerificationCodeException();
            case EXPIRED -> throw new VerificationCodeExpiredException();
            case WRONG_AND_EXHAUSTED, ALREADY_EXHAUSTED -> throw new TooManyVerificationAttemptsException();
            case WRONG -> throw new InvalidVerificationCodeException();
            case VERIFIED -> { }
        }

        user.markPhoneVerified(Instant.now(clock));
        userRepository.save(user);

        return userMapper.toSummary(user);
    }

    /** A valid token for a user who no longer exists is an invalid token: 401, as on GET /me. */
    private User userOrUnauthenticated(UUID callerId) {
        return userRepository.findById(callerId)
                .orElseThrow(() -> new InsufficientAuthenticationException("Authenticated user no longer exists"));
    }
}
