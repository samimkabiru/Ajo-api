package com.theninjadev.ajoapi.exit;

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

/**
 * A replacement taking over a leaver's slot. The participant row now belongs to the
 * replacement, so both parties are snapshotted here.
 */
@Entity
@Table(name = "buy_ins")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class BuyIn {

    @Id
    private UUID id;

    @Column(name = "round_id", nullable = false)
    private UUID roundId;

    @Column(name = "exit_request_id", nullable = false)
    private UUID exitRequestId;

    @Column(name = "participant_id", nullable = false)
    private UUID participantId;

    @Column(name = "leaver_user_id", nullable = false)
    private UUID leaverUserId;

    @Column(name = "replacement_user_id", nullable = false)
    private UUID replacementUserId;

    @Column(name = "amount_kobo", nullable = false)
    private long amountKobo;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 16)
    private BuyInMethod method;

    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "buy_in_transaction_id", nullable = false)
    private UUID buyInTransactionId;

    @Column(name = "refund_transaction_id", nullable = false)
    private UUID refundTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
