package com.theninjadev.ajoapi.group;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupRepository extends JpaRepository<Group, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Group g where g.id = :id")
    Optional<Group> findByIdForUpdate(@Param("id") UUID id);

    List<Group> findAllByIdInAndArchivedAtIsNull(Collection<UUID> ids);

    List<Group> findAllByIdInAndArchivedAtIsNotNull(Collection<UUID> ids);

    /**
     * Whether any round in this group ever had a ledger account. RoundService.activate creates
     * the ROUND_POOL and PARTICIPANT accounts in the same transaction that makes the round
     * ACTIVE, and ledger accounts are never removed, so this is exactly "some round in this
     * group was activated" — asked of the ledger rather than of round status.
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM ledger_accounts la
                WHERE (la.account_type = 'ROUND_POOL'
                       AND la.owner_id IN (SELECT r.id FROM rounds r WHERE r.group_id = :groupId))
                   OR (la.account_type = 'PARTICIPANT'
                       AND la.owner_id IN (SELECT rp.id FROM round_participants rp
                                           JOIN rounds r ON r.id = rp.round_id
                                           WHERE r.group_id = :groupId))
            )
            """, nativeQuery = true)
    boolean hasLedgerHistory(@Param("groupId") UUID groupId);

    @Modifying
    @Query("delete from Group g where g.id = :id")
    void hardDeleteById(@Param("id") UUID id);
}
