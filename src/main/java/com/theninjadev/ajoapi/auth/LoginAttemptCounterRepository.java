package com.theninjadev.ajoapi.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginAttemptCounterRepository extends JpaRepository<LoginAttemptCounter, String> {

    /**
     * Creates the row if there is none; otherwise does nothing. Two first failures for the same
     * number race here, and ON CONFLICT settles it inside Postgres. Catching a unique violation
     * instead would not work: a failed INSERT aborts the whole Postgres transaction, so the
     * lock-and-increment that must follow could never run.
     */
    @Modifying
    @Query(value = """
            INSERT INTO login_attempt_counters (phone, failed_count, window_started_at)
            VALUES (:phone, 0, :now)
            ON CONFLICT (phone) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("phone") String phone, @Param("now") Instant now);

    /**
     * The row, write-locked. Concurrent failures are serialised, so each one sees the previous
     * increment — without the lock, parallel wrong passwords read the same count and the limit
     * never engages.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LoginAttemptCounter c where c.phone = :phone")
    Optional<LoginAttemptCounter> findForUpdate(@Param("phone") String phone);

    /**
     * The block end alone, as a scalar: the pre-login check must not put the entity in the
     * persistence context, or the later findForUpdate would hand back that unlocked, possibly
     * stale copy instead of the row it just locked — and a concurrent increment would be lost.
     */
    @Query("select c.blockedUntil from LoginAttemptCounter c where c.phone = :phone")
    Optional<Instant> findBlockedUntil(@Param("phone") String phone);

    /**
     * No-op when there is no row. Flushes first — password reset calls this with the new hash
     * still pending, and a clear without a flush would silently discard it — then clears, so no
     * stale entity outlives the delete.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from LoginAttemptCounter c where c.phone = :phone")
    void deleteByPhone(@Param("phone") String phone);
}
