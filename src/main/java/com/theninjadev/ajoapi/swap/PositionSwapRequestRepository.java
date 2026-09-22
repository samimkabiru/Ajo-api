package com.theninjadev.ajoapi.swap;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PositionSwapRequestRepository extends JpaRepository<PositionSwapRequest, UUID> {

    List<PositionSwapRequest> findByRoundId(UUID roundId);

    List<PositionSwapRequest> findByRoundIdAndRequesterParticipantIdAndStatus(
            UUID roundId, UUID requesterParticipantId, SwapStatus status);

    List<PositionSwapRequest> findByRoundIdAndTargetParticipantIdAndStatus(
            UUID roundId, UUID targetParticipantId, SwapStatus status);

    @Query("""
        select s from PositionSwapRequest s
        where s.status = com.theninjadev.ajoapi.swap.SwapStatus.PENDING
          and (s.requesterParticipantId in :participantIds or s.targetParticipantId in :participantIds)
        """)
    List<PositionSwapRequest> findPendingInvolving(@Param("participantIds") Collection<UUID> participantIds);
}
