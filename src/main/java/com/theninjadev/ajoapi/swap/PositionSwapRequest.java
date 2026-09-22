package com.theninjadev.ajoapi.swap;

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
@Table(name = "position_swap_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PositionSwapRequest {

    @Id
    private UUID id;

    @Column(name = "round_id", nullable = false)
    private UUID roundId;

    @Column(name = "requester_participant_id", nullable = false)
    private UUID requesterParticipantId;

    @Column(name = "target_participant_id", nullable = false)
    private UUID targetParticipantId;

    @Column(name = "requester_position", nullable = false)
    private int requesterPosition;

    @Column(name = "target_position", nullable = false)
    private int targetPosition;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SwapStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    public void accept(Instant respondedAt) {
        this.status = SwapStatus.ACCEPTED;
        this.respondedAt = respondedAt;
    }

    public void decline(Instant respondedAt) {
        this.status = SwapStatus.DECLINED;
        this.respondedAt = respondedAt;
    }

    public void cancel(Instant respondedAt) {
        this.status = SwapStatus.CANCELLED;
        this.respondedAt = respondedAt;
    }

    public void supersede(Instant respondedAt) {
        this.status = SwapStatus.SUPERSEDED;
        this.respondedAt = respondedAt;
    }
}
