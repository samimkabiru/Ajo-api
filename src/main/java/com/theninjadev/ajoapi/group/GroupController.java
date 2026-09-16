package com.theninjadev.ajoapi.group;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/groups")
@AllArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @PostMapping
    public ResponseEntity<GroupSummary> createGroup(@Valid @RequestBody CreateGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(groupService.createGroup(currentUserId(), request));
    }

    @GetMapping
    public ResponseEntity<List<GroupSummary>> listMyGroups() {
        return ResponseEntity.ok(groupService.listMyGroups(currentUserId()));
    }

    @GetMapping("/my-invites")
    public ResponseEntity<List<GroupInviteSummary>> listMyInvites() {
        return ResponseEntity.ok(groupService.listMyInvites(currentUserId()));
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<GroupDetail> getGroup(@PathVariable UUID groupId) {
        return ResponseEntity.ok(groupService.getGroup(currentUserId(), groupId));
    }

    @PatchMapping("/{groupId}")
    public ResponseEntity<GroupSummary> updateGroup(@PathVariable UUID groupId, @Valid @RequestBody UpdateGroupRequest request) {
        return ResponseEntity.ok(groupService.updateGroup(currentUserId(), groupId, request));
    }

    @PostMapping("/{groupId}/invites")
    public ResponseEntity<GroupInviteSummary> inviteMember(@PathVariable UUID groupId, @Valid @RequestBody InviteMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(groupService.inviteMember(currentUserId(), groupId, request));
    }

    @PostMapping("/invites/{inviteId}/accept")
    public ResponseEntity<GroupMemberSummary> acceptInvite(@PathVariable UUID inviteId) {
        return ResponseEntity.ok(groupService.acceptInvite(currentUserId(), inviteId));
    }

    @PostMapping("/invites/{inviteId}/decline")
    public ResponseEntity<GroupInviteSummary> declineInvite(@PathVariable UUID inviteId) {
        return ResponseEntity.ok(groupService.declineInvite(currentUserId(), inviteId));
    }

    @PostMapping("/invites/{inviteId}/revoke")
    public ResponseEntity<GroupInviteSummary> revokeInvite(@PathVariable UUID inviteId) {
        return ResponseEntity.ok(groupService.revokeInvite(currentUserId(), inviteId));
    }

    @GetMapping("/{groupId}/members")
    public ResponseEntity<List<GroupMemberSummary>> listMembers(@PathVariable UUID groupId) {
        return ResponseEntity.ok(groupService.listMembers(currentUserId(), groupId));
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID groupId, @PathVariable UUID userId) {
        groupService.removeMember(currentUserId(), groupId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{groupId}/leave")
    public ResponseEntity<Void> leaveGroup(@PathVariable UUID groupId) {
        groupService.leaveGroup(currentUserId(), groupId);
        return ResponseEntity.noContent().build();
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
