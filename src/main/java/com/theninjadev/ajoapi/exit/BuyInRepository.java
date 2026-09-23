package com.theninjadev.ajoapi.exit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuyInRepository extends JpaRepository<BuyIn, UUID> {

    Optional<BuyIn> findByIdempotencyKey(String idempotencyKey);

    List<BuyIn> findByRoundId(UUID roundId);

    /** At most one per exit — guaranteed by uq_buy_ins_exit_request. */
    Optional<BuyIn> findByExitRequestId(UUID exitRequestId);

    boolean existsByExitRequestId(UUID exitRequestId);

    List<BuyIn> findByParticipantId(UUID participantId);
}
