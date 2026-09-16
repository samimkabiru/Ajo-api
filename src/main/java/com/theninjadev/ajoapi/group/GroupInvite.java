package com.theninjadev.ajoapi.group;

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
@Table(name = "group_invites")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class GroupInvite {

    @Id
    private UUID id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private InviteStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    public void accept(Instant respondedAt) {
        transitionTo(InviteStatus.ACCEPTED, respondedAt);
    }

    public void decline(Instant respondedAt) {
        transitionTo(InviteStatus.DECLINED, respondedAt);
    }

    public void revoke(Instant respondedAt) {
        transitionTo(InviteStatus.REVOKED, respondedAt);
    }

    private void transitionTo(InviteStatus newStatus, Instant respondedAt) {
        this.status = newStatus;
        this.respondedAt = respondedAt;
    }
}
