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

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public void settle(Instant settledAt) {
        this.settledAt = settledAt;
    }
}
