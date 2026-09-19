package com.theninjadev.ajoapi.round;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoundParticipantRepository extends JpaRepository<RoundParticipant, UUID> {

    List<RoundParticipant> findByRoundId(UUID roundId);

    Optional<RoundParticipant> findByRoundIdAndUserId(UUID roundId, UUID userId);

    boolean existsByRoundIdAndUserId(UUID roundId, UUID userId);

    @Query("""
        SELECT rp.userId
        FROM RoundParticipant rp
        JOIN Round r ON r.id = rp.roundId
        WHERE r.groupId = :groupId
          AND r.status = com.theninjadev.ajoapi.round.RoundStatus.COMPLETED
          AND rp.userId IN :userIds
        GROUP BY rp.userId
        """)
    Set<UUID> findUserIdsWithCompletedRoundsInGroup(@Param("groupId") UUID groupId,
                                                    @Param("userIds") Collection<UUID> userIds);
}
