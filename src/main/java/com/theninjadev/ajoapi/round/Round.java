package com.theninjadev.ajoapi.round;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "rounds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Round {

    @Id
    private UUID id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "contribution_amount_kobo", nullable = false)
    private long contributionAmountKobo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RoundStatus status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "first_payout_date")
    private LocalDate firstPayoutDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void updateTerms(long contributionAmountKobo, LocalDate firstPayoutDate, Instant updatedAt) {
        this.contributionAmountKobo = contributionAmountKobo;
        this.firstPayoutDate = firstPayoutDate;
        this.updatedAt = updatedAt;
    }

    public void cancel(Instant updatedAt) {
        this.status = RoundStatus.CANCELLED;
        this.updatedAt = updatedAt;
    }

    public void activate(Instant activatedAt) {
        this.status = RoundStatus.ACTIVE;
        this.activatedAt = activatedAt;
        this.updatedAt = activatedAt;
    }
}
