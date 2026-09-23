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

@Entity
@Table(name = "exit_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ExitRequest {

    @Id
    private UUID id;

    @Column(name = "round_id", nullable = false)
    private UUID roundId;

    @Column(name = "participant_id", nullable = false)
    private UUID participantId;

    /** Audit snapshot only. Exposure is always recomputed when a decision depends on it. */
    @Column(name = "exposure_at_request", nullable = false)
    private long exposureAtRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ExitStatus status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public void complete(Instant completedAt) {
        this.status = ExitStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    public void cancel(Instant completedAt) {
        this.status = ExitStatus.CANCELLED;
        this.completedAt = completedAt;
    }
}
