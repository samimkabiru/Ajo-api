package com.theninjadev.ajoapi.exit;

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
 * A leaver's refund, paid from their vacant cycle's pot. userId is a snapshot — the
 * leaver may have no participant row by the time anyone reads this.
 */
@Entity
@Table(name = "refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Refund {

    @Id
    private UUID id;

    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    @Column(name = "exit_request_id", nullable = false)
    private UUID exitRequestId;

    @Column(name = "participant_id", nullable = false)
    private UUID participantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "expected_amount_kobo", nullable = false)
    private long expectedAmountKobo;

    @Column(name = "actual_amount_kobo", nullable = false)
    private long actualAmountKobo;

    @Column(name = "ledger_transaction_id", nullable = false)
    private UUID ledgerTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
