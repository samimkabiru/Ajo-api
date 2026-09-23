package com.theninjadev.ajoapi.exit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepaymentRepository extends JpaRepository<Repayment, UUID> {

    Optional<Repayment> findByIdempotencyKey(String idempotencyKey);

    List<Repayment> findByParticipantId(UUID participantId);

    @Query("select coalesce(sum(r.amountKobo), 0) from Repayment r where r.participantId = :participantId")
    long sumAmountKoboByParticipantId(@Param("participantId") UUID participantId);
}
