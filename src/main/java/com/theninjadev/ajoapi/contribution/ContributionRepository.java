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

    @Query("select coalesce(sum(c.amountKobo), 0) from Contribution c where c.cycleId = :cycleId")
    long sumAmountKoboByCycleId(@Param("cycleId") UUID cycleId);
}
