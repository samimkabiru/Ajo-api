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
@Table(name = "cycles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Cycle {

    @Id
    private UUID id;

    @Column(name = "round_id", nullable = false)
    private UUID roundId;

    @Column(name = "cycle_number", nullable = false)
    private int cycleNumber;

    @Column(name = "beneficiary_id")
    private UUID beneficiaryId;

    @Column(name = "opens_on", nullable = false)
    private LocalDate opensOn;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(name = "payout_on", nullable = false)
    private LocalDate payoutOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CycleStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The exit that left this cycle without a beneficiary; null unless vacated. */
    @Column(name = "vacated_by_exit_id")
    private UUID vacatedByExitId;

    /** Whether contributions are accepted on this date. The one place the opening-date test lives. */
    public boolean isOpenForContributionsAt(LocalDate today) {
        return !today.isBefore(opensOn);
    }

    /**
     * What the API reports: SCHEDULED reads as OPEN once the cycle can take contributions. The
     * stored column means "has anyone contributed yet", which contribute relies on, so it is left
     * alone. PAID, VACANT and SETTLED are real events and always report themselves.
     */
    public CycleStatus effectiveStatusAt(LocalDate today) {
        return status == CycleStatus.SCHEDULED && isOpenForContributionsAt(today) ? CycleStatus.OPEN : status;
    }

    public void open() {
        this.status = CycleStatus.OPEN;
    }

    public void markPaid() {
        this.status = CycleStatus.PAID;
    }

    public void reassignBeneficiary(UUID participantId) {
        this.beneficiaryId = participantId;
    }

    /** Status, beneficiary and provenance change together so they can never drift apart. */
    public void markVacant(UUID exitId) {
        this.status = CycleStatus.VACANT;
        this.beneficiaryId = null;
        this.vacatedByExitId = exitId;
    }

    public void markSettled() {
        this.status = CycleStatus.SETTLED;
    }
}
