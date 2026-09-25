package com.theninjadev.ajoapi.payout;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShortfallClaimRepository extends JpaRepository<ShortfallClaim, UUID> {

    Optional<ShortfallClaim> findByCycleId(UUID cycleId);

    List<ShortfallClaim> findByParticipantId(UUID participantId);

    List<ShortfallClaim> findByCycleIdIn(Collection<UUID> cycleIds);

    List<ShortfallClaim> findByParticipantIdAndSettledAtIsNull(UUID participantId);

    /** Not fully settled, oldest first — the order a vacant pot pays them in. */
    @Query("""
        SELECT sc FROM ShortfallClaim sc JOIN Cycle c ON c.id = sc.cycleId
        WHERE c.roundId = :roundId AND sc.settledAmountKobo < sc.amountKobo
        ORDER BY sc.createdAt ASC
        """)
    List<ShortfallClaim> findOpenByRoundIdOldestFirst(@Param("roundId") UUID roundId);
}
