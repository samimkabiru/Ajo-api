package com.theninjadev.ajoapi.verification;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationCodeRepository extends JpaRepository<VerificationCode, UUID> {

    /** The live code — at most one, guaranteed by uq_verification_codes_active. */
    Optional<VerificationCode> findByUserIdAndPurposeAndConsumedAtIsNull(UUID userId, VerificationPurpose purpose);

    /**
     * The live code, row-locked. Verification uses this so concurrent guesses are serialised:
     * without the lock, parallel wrong attempts each read the same count and the attempt cap
     * never engages.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from VerificationCode c
            where c.userId = :userId and c.purpose = :purpose and c.consumedAt is null
            """)
    Optional<VerificationCode> findLiveForUpdate(@Param("userId") UUID userId,
                                                 @Param("purpose") VerificationPurpose purpose);

    /** Codes created since the given instant, consumed ones included — each was an SMS sent. Newest first. */
    List<VerificationCode> findByUserIdAndPurposeAndCreatedAtAfterOrderByCreatedAtDesc(
            UUID userId, VerificationPurpose purpose, Instant since);
}
