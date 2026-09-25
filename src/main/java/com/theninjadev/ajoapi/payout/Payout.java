package com.theninjadev.ajoapi.payout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payouts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Payout {

    @Id
    private UUID id;

    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    @Column(name = "participant_id", nullable = false)
    private UUID participantId;

    @Column(name = "expected_amount_kobo", nullable = false)
    private long expectedAmountKobo;

    @Column(name = "actual_amount_kobo", nullable = false)
    private long actualAmountKobo;

    /** The beneficiary's own arrears, held back from this payout to settle the claims they caused. */
    @Column(name = "arrears_withheld_kobo", nullable = false)
    private long arrearsWithheldKobo;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 16)
    private PayoutMethod method;

    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    /** Null when the whole payout was withheld for arrears: nothing was paid, so nothing was posted. */
    @Column(name = "ledger_transaction_id")
    private UUID ledgerTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
