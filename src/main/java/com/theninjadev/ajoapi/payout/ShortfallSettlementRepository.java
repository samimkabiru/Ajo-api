package com.theninjadev.ajoapi.payout;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShortfallSettlementRepository extends JpaRepository<ShortfallSettlement, UUID> {

    List<ShortfallSettlement> findByClaimId(UUID claimId);

    List<ShortfallSettlement> findByFundedByCycleId(UUID fundedByCycleId);

    /** How much of a vacant cycle's pot earlier settlement passes have already paid out. */
    @Query("select coalesce(sum(s.amountKobo), 0) from ShortfallSettlement s where s.fundedByCycleId = :cycleId")
    long sumAmountKoboByFundedByCycleId(@Param("cycleId") UUID cycleId);

    @Query("select coalesce(sum(s.amountKobo), 0) from ShortfallSettlement s where s.claimId = :claimId")
    long sumAmountKoboByClaimId(@Param("claimId") UUID claimId);

    @Query("""
        select coalesce(sum(s.amountKobo), 0)
        from ShortfallSettlement s
        join ShortfallClaim c on c.id = s.claimId
        where c.participantId = :participantId
        """)
    long sumAmountKoboByClaimParticipantId(@Param("participantId") UUID participantId);
}
