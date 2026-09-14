package com.theninjadev.ajoapi.ledger;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    @Query("select coalesce(sum(e.amountKobo), 0) from LedgerEntry e where e.accountId = :accountId")
    long sumAmountKoboByAccountId(@Param("accountId") UUID accountId);
}
