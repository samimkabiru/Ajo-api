package com.theninjadev.ajoapi.exit;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    /** At most one per exit — guaranteed by uq_refunds_exit_request. */
    Optional<Refund> findByExitRequestId(UUID exitRequestId);

    List<Refund> findByCycleId(UUID cycleId);

    List<Refund> findByCycleIdIn(Collection<UUID> cycleIds);

    List<Refund> findByParticipantId(UUID participantId);

    boolean existsByExitRequestId(UUID exitRequestId);

    @Query("select coalesce(sum(r.actualAmountKobo), 0) from Refund r where r.participantId = :participantId")
    long sumActualAmountKoboByParticipantId(@Param("participantId") UUID participantId);
}
