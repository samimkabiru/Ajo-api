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

    public void open() {
        this.status = CycleStatus.OPEN;
    }

    public void markPaid() {
        this.status = CycleStatus.PAID;
    }
}
