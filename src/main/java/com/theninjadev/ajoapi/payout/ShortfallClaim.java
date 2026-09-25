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

@Entity
@Table(name = "shortfall_claims")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ShortfallClaim {

    @Id
    private UUID id;

    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    @Column(name = "participant_id", nullable = false)
    private UUID participantId;

    @Column(name = "amount_kobo", nullable = false)
    private long amountKobo;

    @Column(name = "settled_amount_kobo", nullable = false)
    private long settledAmountKobo;

    /** Set only once the claim is fully settled; a part-paid claim has a non-zero settledAmountKobo and no settledAt. */
    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Unguarded — the caller checks the arithmetic. */
    public void settle(long additionalAmountKobo, Instant settledAt) {
        this.settledAmountKobo += additionalAmountKobo;
        if (this.settledAmountKobo == this.amountKobo)
            this.settledAt = settledAt;
    }
}
