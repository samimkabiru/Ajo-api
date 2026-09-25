package com.theninjadev.ajoapi.payout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One payment made against a shortfall claim. A claim may be paid in several
 * instalments, each funded by a different vacant cycle.
 */
@Entity
@Table(name = "shortfall_settlements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ShortfallSettlement {

    @Id
    private UUID id;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(name = "funded_by_cycle_id", nullable = false)
    private UUID fundedByCycleId;

    @Column(name = "amount_kobo", nullable = false)
    private long amountKobo;

    @Column(name = "ledger_transaction_id", nullable = false)
    private UUID ledgerTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
