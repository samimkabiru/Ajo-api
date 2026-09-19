package com.theninjadev.ajoapi.ledger;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {
    Optional<LedgerAccount> findByAccountTypeAndOwnerId(AccountType accountType, UUID ownerId);
}
