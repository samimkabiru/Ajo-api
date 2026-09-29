package com.theninjadev.ajoapi.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Failed logins for one normalised phone number, which may belong to no user. Rows are created
 * by LoginAttemptCounterRepository.insertIfAbsent, never by save() on a new instance.
 */
@Entity
@Table(name = "login_attempt_counters")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class LoginAttemptCounter {

    @Id
    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "window_started_at", nullable = false)
    private Instant windowStartedAt;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    public boolean isBlockedAt(Instant now) {
        return blockedUntil != null && blockedUntil.isAfter(now);
    }

    /**
     * Counts one failure. Must not be called while blocked: attempts during a block change
     * nothing, otherwise a loop could keep someone locked out forever.
     */
    public void recordFailure(Instant now, LoginRateLimitProperties limits) {
        if (!windowStartedAt.plus(limits.window()).isAfter(now)) {
            failedCount = 0;
            windowStartedAt = now;
            blockedUntil = null;
        }

        failedCount += 1;

        // Blocking starts a fresh window at the block's end, so the number gets a full set of
        // attempts when it lifts instead of being re-blocked by its next single mistake.
        if (failedCount >= limits.maxAttempts()) {
            blockedUntil = now.plus(limits.blockDuration());
            failedCount = 0;
            windowStartedAt = blockedUntil;
        }
    }
}
