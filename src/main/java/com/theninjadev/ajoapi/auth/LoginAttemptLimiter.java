package com.theninjadev.ajoapi.auth;

import java.time.Clock;
import java.time.Instant;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Failed-login counting per normalised phone number. The number is counted whether or not an
 * account exists, so blocking reveals nothing about who is registered.
 *
 * Returns outcomes rather than throwing: an exception crossing this joined transaction would
 * mark the caller's whole transaction rollback-only, and the increment would be lost.
 */
@Component
@AllArgsConstructor
public class LoginAttemptLimiter {

    public enum FailureOutcome {
        RECORDED,           // counted (possibly setting a block); the caller answers 401
        ALREADY_BLOCKED     // a concurrent failure set the block first; nothing written, answer 429
    }

    private final LoginAttemptCounterRepository repository;
    private final LoginRateLimitProperties limits;
    private final Clock clock;

    /** Read-only, unlocked, and writes nothing — a blocked request must not extend its block. */
    @Transactional
    public boolean isBlocked(String phone) {
        Instant now = Instant.now(clock);
        return repository.findBlockedUntil(phone)
                .map(blockedUntil -> blockedUntil.isAfter(now))
                .orElse(false);
    }

    @Transactional
    public FailureOutcome recordFailure(String phone) {
        Instant now = Instant.now(clock);
        repository.insertIfAbsent(phone, now);

        // Can be empty despite the insert: a successful login or password reset can delete the
        // row between the two statements (ON CONFLICT DO NOTHING takes no lock, so the delete
        // is not held off). Losing one failure in that race is harmless — the number just
        // proved it is in legitimate hands — and it must not become an NPE in the login path.
        LoginAttemptCounter counter = repository.findForUpdate(phone).orElse(null);
        if (counter == null)
            return FailureOutcome.RECORDED;

        if (counter.isBlockedAt(now))
            return FailureOutcome.ALREADY_BLOCKED;

        counter.recordFailure(now, limits);
        repository.save(counter);
        return FailureOutcome.RECORDED;
    }

    /** After a successful login or password reset: the number starts clean. */
    @Transactional
    public void clear(String phone) {
        repository.deleteByPhone(phone);
    }
}
