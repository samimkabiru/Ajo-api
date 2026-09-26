package com.theninjadev.ajoapi.verification;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a one-time code is issued and checked, for every purpose. Phone verification
 * and password reset both go through here, so the security logic — generation, hashing, rate
 * limits, attempt cap, consumption — exists once and cannot drift between flows.
 *
 * Every lookup filters on purpose: a code proves control of the channel for one purpose only.
 * The plaintext code exists only as a local variable and in the SMS body — never returned,
 * logged here, or stored.
 */
@Component
@AllArgsConstructor
class VerificationCodes {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final VerificationCodeRepository verificationCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final SmsSender smsSender;
    private final OtpProperties otpProperties;
    private final Clock clock;

    record Issued(Instant expiresAt, Instant resendAvailableAt) {}

    /**
     * How a check ended. Returned rather than thrown: an exception crossing this joined
     * transaction would mark the caller's whole transaction rollback-only, so a caller that
     * maps outcomes to its own exception (password reset) would fail at commit with a 500.
     * Each caller's own noRollbackFor decides what persists.
     */
    enum CheckResult {
        VERIFIED(true),              // matched; code consumed
        WRONG(true),                 // did not match; increment persisted
        WRONG_AND_EXHAUSTED(true),   // did not match and reached the cap; code consumed
        EXPIRED(false),              // past its TTL; code consumed
        ALREADY_EXHAUSTED(false),    // cap already reached (max-attempts lowered mid-flight); code consumed
        NO_LIVE_CODE(false);

        /** Whether this outcome ran a BCrypt comparison — callers that must hide timing need to know. */
        final boolean ranBcrypt;

        CheckResult(boolean ranBcrypt) {
            this.ranBcrypt = ranBcrypt;
        }
    }

    /**
     * Issues a new code and texts it. Throws TooManyVerificationRequestsException or
     * VerificationResendTooSoonException when rate-limited; callers decide what the client sees.
     */
    @Transactional
    public Issued issue(UUID userId, String e164Phone, VerificationPurpose purpose, String smsLead) {
        Instant now = Instant.now(clock);

        // Rate limit: codes sent in the window (consumed ones count — each was an SMS), then the cooldown.
        List<VerificationCode> recent = verificationCodeRepository
                .findByUserIdAndPurposeAndCreatedAtAfterOrderByCreatedAtDesc(
                        userId, purpose, now.minus(otpProperties.requestWindow()));
        if (recent.size() >= otpProperties.maxRequestsPerWindow())
            throw new TooManyVerificationRequestsException();
        if (!recent.isEmpty() && recent.getFirst().getCreatedAt().plus(otpProperties.resendCooldown()).isAfter(now))
            throw new VerificationResendTooSoonException();

        // A new code invalidates the old one, so a code glimpsed over someone's shoulder dies the
        // moment another is requested. Flushed immediately: Hibernate runs INSERTs before UPDATEs
        // at flush time, so without this the new row would be inserted while the old one still
        // looks live, and uq_verification_codes_active would reject every resend.
        verificationCodeRepository.findByUserIdAndPurposeAndConsumedAtIsNull(userId, purpose)
                .ifPresent(live -> {
                    live.consume(now);
                    verificationCodeRepository.saveAndFlush(live);
                });

        String code = generateCode();
        Instant expiresAt = now.plus(otpProperties.ttl());

        VerificationCode verificationCode = VerificationCode.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .purpose(purpose)
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

        smsSender.send(e164Phone,
                smsLead + code + ". It expires in " + otpProperties.ttl().toMinutes() + " minutes.");

        return new Issued(expiresAt, now.plus(otpProperties.resendCooldown()));
    }

    /**
     * Checks a submitted code against the live code for this purpose, recording the outcome.
     * Callers must run inside a transaction whose noRollbackFor covers the exceptions they throw
     * for WRONG / WRONG_AND_EXHAUSTED / EXPIRED — otherwise the increment or consume is undone.
     */
    @Transactional
    public CheckResult check(UUID userId, VerificationPurpose purpose, String submittedCode) {
        // Row-locked: concurrent guesses are serialised, so each one sees the previous increment.
        VerificationCode code = verificationCodeRepository.findLiveForUpdate(userId, purpose).orElse(null);
        if (code == null)
            return CheckResult.NO_LIVE_CODE;

        Instant now = Instant.now(clock);

        if (!code.getExpiresAt().isAfter(now)) {
            code.consume(now);
            verificationCodeRepository.save(code);
            return CheckResult.EXPIRED;
        }

        // Backstop only: the failure path below consumes the code as it reaches the cap, so this
        // fires only if max-attempts was lowered while a code was live.
        if (code.getAttempts() >= otpProperties.maxAttempts()) {
            code.consume(now);
            verificationCodeRepository.save(code);
            return CheckResult.ALREADY_EXHAUSTED;
        }

        // Constant-time comparison against the hash — never equals().
        if (!passwordEncoder.matches(submittedCode, code.getCodeHash())) {
            code.recordFailedAttempt();
            if (code.getAttempts() >= otpProperties.maxAttempts()) {
                code.consume(now);            // dead, not merely exhausted
                verificationCodeRepository.save(code);
                return CheckResult.WRONG_AND_EXHAUSTED;
            }
            verificationCodeRepository.save(code);
            return CheckResult.WRONG;
        }

        code.consume(now);
        verificationCodeRepository.save(code);
        return CheckResult.VERIFIED;
    }

    /** Uniform over the full range for the length — 100000..999999 for six digits — with no modulo bias. */
    private String generateCode() {
        int lower = (int) Math.pow(10, otpProperties.codeLength() - 1);
        return Integer.toString(SECURE_RANDOM.nextInt(9 * lower) + lower);
    }
}
