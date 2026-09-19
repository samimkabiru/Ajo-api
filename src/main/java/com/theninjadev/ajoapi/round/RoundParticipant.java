package com.theninjadev.ajoapi.round;

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
@Table(name = "round_participants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RoundParticipant {

    @Id
    private UUID id;

    @Column(name = "round_id", nullable = false)
    private UUID roundId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "payout_position")
    private Integer payoutPosition;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ParticipantStatus status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    public void assignPosition(int payoutPosition) {
        this.payoutPosition = payoutPosition;
    }
}
