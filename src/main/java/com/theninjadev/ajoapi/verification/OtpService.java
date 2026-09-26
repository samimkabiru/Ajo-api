package com.theninjadev.ajoapi.verification;

import com.theninjadev.ajoapi.auth.User;
import com.theninjadev.ajoapi.auth.UserMapper;
import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.auth.UserSummary;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and checks one-time codes sent by SMS. The plaintext code exists only as a local
 * variable and in the SMS body: it is never returned, logged here, or stored.
 */
@Service
@AllArgsConstructor
public class OtpService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final VerificationPurpose PURPOSE = VerificationPurpose.PHONE_VERIFICATION;

    private final VerificationCodeRepository verificationCodeRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SmsSender smsSender;
    private final OtpProperties otpProperties;
    private final Clock clock;

    @Transactional
    public VerificationCodeRequested requestCode(UUID callerId) {
        User user = userOrUnauthenticated(callerId);
        if (user.isPhoneVerified())
            throw new PhoneAlreadyVerifiedException();

        Instant now = Instant.now(clock);

        // Rate limit: codes sent in the window (consumed ones count — each was an SMS), then the cooldown.
        List<VerificationCode> recent = verificationCodeRepository
                .findByUserIdAndPurposeAndCreatedAtAfterOrderByCreatedAtDesc(
                        callerId, PURPOSE, now.minus(otpProperties.requestWindow()));
        if (recent.size() >= otpProperties.maxRequestsPerWindow())
            throw new TooManyVerificationRequestsException();
        if (!recent.isEmpty() && recent.getFirst().getCreatedAt().plus(otpProperties.resendCooldown()).isAfter(now))
            throw new VerificationResendTooSoonException();

        // A new code invalidates the old one, so a code glimpsed over someone's shoulder dies the
        // moment another is requested. Flushed immediately: Hibernate runs INSERTs before UPDATEs
        // at flush time, so without this the new row would be inserted while the old one still
        // looks live, and uq_verification_codes_active would reject every resend.
        verificationCodeRepository.findByUserIdAndPurposeAndConsumedAtIsNull(callerId, PURPOSE)
                .ifPresent(live -> {
                    live.consume(now);
                    verificationCodeRepository.saveAndFlush(live);
                });

        String code = generateCode();
        Instant expiresAt = now.plus(otpProperties.ttl());

        VerificationCode verificationCode = VerificationCode.builder()
                .id(UUID.randomUUID())
                .userId(callerId)
                .purpose(PURPOSE)
                // BCrypt, never a bare SHA-256. A six-digit code has only a million possible
                // values: an unsalted fast hash from a leaked database is reversed in under a
                // second. BCrypt's per-row salt and deliberate slowness are what make this stored
                // value worth anything. Do not "optimise" this.
                .codeHash(passwordEncoder.encode(code))
                .attempts(0)
                .expiresAt(expiresAt)
                .createdAt(now)
                .build();

        // The partial unique index is the backstop: two simultaneous requests both pass the checks
        // above, and the second insert collides. That is a resend arriving too soon, not a 500.
        try {
            verificationCodeRepository.saveAndFlush(verificationCode);
        } catch (DataIntegrityViolationException e) {
            throw new VerificationResendTooSoonException();
        }

        smsSender.send(user.getPhone(),
                "Your Ajo verification code is " + code + ". It expires in "
                        + otpProperties.ttl().toMinutes() + " minutes.");

        return new VerificationCodeRequested(expiresAt, now.plus(otpProperties.resendCooldown()));
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

        // Row-locked: concurrent guesses are serialised, so each one sees the previous increment.
        VerificationCode code = verificationCodeRepository.findLiveForUpdate(callerId, PURPOSE)
                .orElseThrow(NoActiveVerificationCodeException::new);

        Instant now = Instant.now(clock);

        if (!code.getExpiresAt().isAfter(now)) {
            code.consume(now);
            verificationCodeRepository.save(code);
            throw new VerificationCodeExpiredException();
        }

        // Backstop only: the failure path below consumes the code as it reaches the cap, so this
        // fires only if max-attempts was lowered while a code was live.
        if (code.getAttempts() >= otpProperties.maxAttempts()) {
            code.consume(now);
            verificationCodeRepository.save(code);
            throw new TooManyVerificationAttemptsException();
        }

        // Constant-time comparison against the hash — never equals().
        if (!passwordEncoder.matches(submittedCode, code.getCodeHash())) {
            code.recordFailedAttempt();
            if (code.getAttempts() >= otpProperties.maxAttempts()) {
                code.consume(now);            // dead, not merely exhausted
                verificationCodeRepository.save(code);
                throw new TooManyVerificationAttemptsException();
            }
            verificationCodeRepository.save(code);
            throw new InvalidVerificationCodeException();
        }

        code.consume(now);
        verificationCodeRepository.save(code);

        user.markPhoneVerified(now);
        userRepository.save(user);

        return userMapper.toSummary(user);
    }

    /** Uniform over the full range for the length — 100000..999999 for six digits — with no modulo bias. */
    private String generateCode() {
        int lower = (int) Math.pow(10, otpProperties.codeLength() - 1);
        return Integer.toString(SECURE_RANDOM.nextInt(9 * lower) + lower);
    }

    /** A valid token for a user who no longer exists is an invalid token: 401, as on GET /me. */
    private User userOrUnauthenticated(UUID callerId) {
        return userRepository.findById(callerId)
                .orElseThrow(() -> new InsufficientAuthenticationException("Authenticated user no longer exists"));
    }
}
