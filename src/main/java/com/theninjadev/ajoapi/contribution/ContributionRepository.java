package com.theninjadev.ajoapi.contribution;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContributionRepository extends JpaRepository<Contribution, UUID> {

    Optional<Contribution> findByIdempotencyKey(String idempotencyKey);

    List<Contribution> findByCycleId(UUID cycleId);

    List<Contribution> findByParticipantId(UUID participantId);

    List<Contribution> findByCycleIdIn(Collection<UUID> cycleIds);

    boolean existsByCycleIdAndParticipantId(UUID cycleId, UUID participantId);

    /** A participant row belongs to one round, so this is already scoped to that round. */
    @Query("""
            select c.cycleId from Contribution c
            where c.participantId = :participantId
            """)
    List<UUID> findContributedCycleIdsByParticipantId(@Param("participantId") UUID participantId);

    @Query("select coalesce(sum(c.amountKobo), 0) from Contribution c where c.cycleId = :cycleId")
    long sumAmountKoboByCycleId(@Param("cycleId") UUID cycleId);

    @Query("select coalesce(sum(c.amountKobo), 0) from Contribution c where c.participantId = :participantId")
    long sumAmountKoboByParticipantId(@Param("participantId") UUID participantId);
}
