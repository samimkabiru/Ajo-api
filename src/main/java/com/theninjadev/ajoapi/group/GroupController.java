package com.theninjadev.ajoapi.group;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Groups", description = "The people who save together: create a group, invite members by phone, manage roles.")
@RequestMapping("/groups")
@AllArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @Operation(summary = "Create a group",
            description = "The caller becomes its first ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Group created."),
            @ApiResponse(responseCode = "400", description = "Validation failed.")
    })
    @PostMapping
    public ResponseEntity<GroupSummary> createGroup(@Valid @RequestBody CreateGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(groupService.createGroup(currentUserId(), request));
    }

    @Operation(summary = "List my groups")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Groups you belong to.")
    })
    @GetMapping
    public ResponseEntity<List<GroupSummary>> listMyGroups() {
        return ResponseEntity.ok(groupService.listMyGroups(currentUserId()));
    }

    @Operation(summary = "List invites sent to me")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pending invites addressed to your phone number.")
    })
    @GetMapping("/my-invites")
    public ResponseEntity<List<GroupInviteSummary>> listMyInvites() {
        return ResponseEntity.ok(groupService.listMyInvites(currentUserId()));
    }

    @Operation(summary = "Get a group")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The group with its members."),
            @ApiResponse(responseCode = "404", description = "The group does not exist, or you are not a member of it.")
    })
    @GetMapping("/{groupId}")
    public ResponseEntity<GroupDetail> getGroup(@PathVariable UUID groupId) {
        return ResponseEntity.ok(groupService.getGroup(currentUserId(), groupId));
    }

    @Operation(summary = "Update a group's name or description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Group updated."),
            @ApiResponse(responseCode = "400", description = "Validation failed."),
            @ApiResponse(responseCode = "403", description = "You are not an admin of this group."),
            @ApiResponse(responseCode = "404", description = "The group does not exist, or you are not a member of it.")
    })
    @PatchMapping("/{groupId}")
    public ResponseEntity<GroupSummary> updateGroup(@PathVariable UUID groupId, @Valid @RequestBody UpdateGroupRequest request) {
        return ResponseEntity.ok(groupService.updateGroup(currentUserId(), groupId, request));
    }

    @Operation(summary = "Invite someone by phone number",
            description = "The invitee does not need an account yet; the invite is matched to their phone number.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Invite created."),
            @ApiResponse(responseCode = "400", description = "Validation failed, or the phone number is not a valid Nigerian number."),
            @ApiResponse(responseCode = "403", description = "You are not an admin of this group."),
            @ApiResponse(responseCode = "404", description = "The group does not exist, or you are not a member of it."),
            @ApiResponse(responseCode = "409", description = "That phone number already belongs to a member, or already has a pending invite.")
    })
    @PostMapping("/{groupId}/invites")
    public ResponseEntity<GroupInviteSummary> inviteMember(@PathVariable UUID groupId, @Valid @RequestBody InviteMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(groupService.inviteMember(currentUserId(), groupId, request));
    }

    @Operation(summary = "Accept an invite")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "You are now a member of the group."),
            @ApiResponse(responseCode = "404", description = "The invite does not exist or was not sent to you."),
            @ApiResponse(responseCode = "409", description = "The invite has already been accepted, declined or revoked.")
    })
    @PostMapping("/invites/{inviteId}/accept")
    public ResponseEntity<GroupMemberSummary> acceptInvite(@PathVariable UUID inviteId) {
        return ResponseEntity.ok(groupService.acceptInvite(currentUserId(), inviteId));
    }

    @Operation(summary = "Decline an invite")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invite declined."),
            @ApiResponse(responseCode = "404", description = "The invite does not exist or was not sent to you."),
            @ApiResponse(responseCode = "409", description = "The invite has already been accepted, declined or revoked.")
    })
    @PostMapping("/invites/{inviteId}/decline")
    public ResponseEntity<GroupInviteSummary> declineInvite(@PathVariable UUID inviteId) {
        return ResponseEntity.ok(groupService.declineInvite(currentUserId(), inviteId));
    }

    @Operation(summary = "Revoke an invite")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invite revoked."),
            @ApiResponse(responseCode = "403", description = "You are not an admin of the inviting group."),
            @ApiResponse(responseCode = "404", description = "The invite or its group does not exist, or you are not a member of the group."),
            @ApiResponse(responseCode = "409", description = "The invite has already been accepted, declined or revoked.")
    })
    @PostMapping("/invites/{inviteId}/revoke")
    public ResponseEntity<GroupInviteSummary> revokeInvite(@PathVariable UUID inviteId) {
        return ResponseEntity.ok(groupService.revokeInvite(currentUserId(), inviteId));
    }

    @Operation(summary = "List members")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Members with their roles."),
            @ApiResponse(responseCode = "404", description = "The group does not exist, or you are not a member of it.")
    })
    @GetMapping("/{groupId}/members")
    public ResponseEntity<List<GroupMemberSummary>> listMembers(@PathVariable UUID groupId) {
        return ResponseEntity.ok(groupService.listMembers(currentUserId(), groupId));
    }

    @Operation(summary = "Remove a member",
            description = "Admins remove other members. To remove yourself, use leave.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Member removed."),
            @ApiResponse(responseCode = "400", description = "Admins cannot remove themselves; use leave instead."),
            @ApiResponse(responseCode = "403", description = "You are not an admin of this group."),
            @ApiResponse(responseCode = "404", description = "The group does not exist, or you or the target are not members of it."),
            @ApiResponse(responseCode = "409", description = "The last remaining admin cannot be removed.")
    })
    @DeleteMapping("/{groupId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID groupId, @PathVariable UUID userId) {
        groupService.removeMember(currentUserId(), groupId, userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Leave a group")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "You have left the group."),
            @ApiResponse(responseCode = "404", description = "The group does not exist, or you are not a member of it."),
            @ApiResponse(responseCode = "409", description = "You are the last remaining admin and cannot leave.")
    })
    @PostMapping("/{groupId}/leave")
    public ResponseEntity<Void> leaveGroup(@PathVariable UUID groupId) {
        groupService.leaveGroup(currentUserId(), groupId);
        return ResponseEntity.noContent().build();
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
