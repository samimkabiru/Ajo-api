package com.theninjadev.ajoapi.round;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoundRepository extends JpaRepository<Round, UUID> {

    List<Round> findByGroupId(UUID groupId);

    boolean existsByGroupIdAndStatusIn(UUID groupId, Collection<RoundStatus> statuses);

    @Modifying
    @Query("delete from Round r where r.groupId = :groupId")
    void deleteAllByGroupId(@Param("groupId") UUID groupId);

    /**
     * Whether this round ever had a ledger account. RoundService.activate creates the ROUND_POOL
     * and PARTICIPANT accounts in the same transaction that makes the round ACTIVE, and ledger
     * accounts are never removed, so this is exactly "this round was activated" — asked of the
     * ledger rather than of round status. The round-scoped sibling of GroupRepository.hasLedgerHistory.
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM ledger_accounts la
                WHERE (la.account_type = 'ROUND_POOL' AND la.owner_id = :roundId)
                   OR (la.account_type = 'PARTICIPANT'
                       AND la.owner_id IN (SELECT rp.id FROM round_participants rp WHERE rp.round_id = :roundId))
            )
            """, nativeQuery = true)
    boolean hasLedgerHistory(@Param("roundId") UUID roundId);
}
