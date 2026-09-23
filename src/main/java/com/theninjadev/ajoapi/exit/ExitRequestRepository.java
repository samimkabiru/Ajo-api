package com.theninjadev.ajoapi.exit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExitRequestRepository extends JpaRepository<ExitRequest, UUID> {

    List<ExitRequest> findByRoundId(UUID roundId);

    /** At most one PENDING_SETTLEMENT row per participant — guaranteed by uq_exit_requests_open. */
    Optional<ExitRequest> findByParticipantIdAndStatus(UUID participantId, ExitStatus status);

    List<ExitRequest> findByParticipantId(UUID participantId);

    boolean existsByParticipantIdAndStatus(UUID participantId, ExitStatus status);
}
