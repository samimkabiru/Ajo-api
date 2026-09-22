package com.theninjadev.ajoapi.payout;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    Optional<Payout> findByIdempotencyKey(String idempotencyKey);

    Optional<Payout> findByCycleId(UUID cycleId);

    List<Payout> findByParticipantId(UUID participantId);

    boolean existsByCycleId(UUID cycleId);

    List<Payout> findByCycleIdIn(Collection<UUID> cycleIds);
}
