package com.theninjadev.ajoapi.payout;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShortfallClaimRepository extends JpaRepository<ShortfallClaim, UUID> {

    Optional<ShortfallClaim> findByCycleId(UUID cycleId);

    List<ShortfallClaim> findByParticipantId(UUID participantId);

    List<ShortfallClaim> findByCycleIdIn(Collection<UUID> cycleIds);

    List<ShortfallClaim> findByParticipantIdAndSettledAtIsNull(UUID participantId);
}
