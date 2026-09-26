package com.theninjadev.ajoapi.group;

import com.theninjadev.ajoapi.auth.PhoneNumberNormalizer;
import com.theninjadev.ajoapi.auth.User;
import com.theninjadev.ajoapi.auth.UserMapper;
import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.verification.PhoneNotVerifiedException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.AllArgsConstructor;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupInviteRepository groupInviteRepository;
    private final UserRepository userRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final GroupMapper groupMapper;
    private final UserMapper userMapper;
    private final Clock clock;

    @Transactional
    public GroupSummary createGroup(UUID callerId, CreateGroupRequest request) {
        requireVerifiedPhone(callerId);
        Instant now = Instant.now(clock);

        Group group = groupRepository.save(Group.builder()
                .id(UUID.randomUUID())
                .name(request.name())
                .description(request.description())
                .createdBy(callerId)
                .createdAt(now)
                .updatedAt(now)
                .build());

        groupMemberRepository.save(GroupMember.builder()
                .id(UUID.randomUUID())
                .groupId(group.getId())
                .userId(callerId)
                .role(GroupRole.ADMIN)
                .joinedAt(now)
                .build());

        return groupMapper.toSummary(group);
    }

    public List<GroupSummary> listMyGroups(UUID callerId) {
        List<UUID> groupIds = groupMemberRepository.findByUserId(callerId).stream()
                .map(GroupMember::getGroupId)
                .toList();
        return groupRepository.findAllById(groupIds).stream()
                .map(groupMapper::toSummary)
                .toList();
    }

    public GroupDetail getGroup(UUID callerId, UUID groupId) {
        Group group = getGroupOrThrow(groupId);
        requireMembership(groupId, callerId);

        List<GroupMemberSummary> members = memberSummaries(groupId);

        return new GroupDetail(group.getId(), group.getName(), group.getDescription(),
                group.getCreatedBy(), group.getCreatedAt(), group.getUpdatedAt(), members);
    }

    @Transactional
    public GroupSummary updateGroup(UUID callerId, UUID groupId, UpdateGroupRequest request) {
        Group group = getGroupOrThrow(groupId);
        requireAdmin(groupId, callerId);

        group.update(request.name(), request.description(), Instant.now(clock));
        groupRepository.save(group);

        return groupMapper.toSummary(group);
    }

    @Transactional
    public GroupInviteSummary inviteMember(UUID callerId, UUID groupId, InviteMemberRequest request) {
        getGroupOrThrow(groupId);
        requireAdmin(groupId, callerId);

        String normalizedPhone = phoneNumberNormalizer.normalize(request.phone());

        boolean alreadyMember = userRepository.findByPhone(normalizedPhone)
                .map(user -> groupMemberRepository.existsByGroupIdAndUserId(groupId, user.getId()))
                .orElse(false);
        if (alreadyMember)
            throw new AlreadyGroupMemberException();

        if (groupInviteRepository.findByGroupIdAndPhoneAndStatus(groupId, normalizedPhone, InviteStatus.PENDING).isPresent())
            throw new DuplicatePendingInviteException();

        GroupInvite invite = groupInviteRepository.save(GroupInvite.builder()
                .id(UUID.randomUUID())
                .groupId(groupId)
                .phone(normalizedPhone)
                .invitedBy(callerId)
                .status(InviteStatus.PENDING)
                .createdAt(Instant.now(clock))
                .build());

        return groupMapper.toInviteSummary(invite);
    }

    public List<GroupInviteSummary> listMyInvites(UUID callerId) {
        User caller = getCallerOrThrow(callerId);
        return groupInviteRepository.findByPhoneAndStatus(caller.getPhone(), InviteStatus.PENDING).stream()
                .map(groupMapper::toInviteSummary)
                .toList();
    }

    @Transactional
    public GroupMemberSummary acceptInvite(UUID callerId, UUID inviteId) {
        requireVerifiedPhone(callerId);
        GroupInvite invite = groupInviteRepository.findById(inviteId).orElseThrow(InviteNotFoundException::new);
        User caller = getCallerOrThrow(callerId);
        requireInvitee(invite, caller);
        requirePending(invite);

        Instant now = Instant.now(clock);
        invite.accept(now);
        groupInviteRepository.save(invite);

        GroupMember member = groupMemberRepository.save(GroupMember.builder()
                .id(UUID.randomUUID())
                .groupId(invite.getGroupId())
                .userId(callerId)
                .role(GroupRole.MEMBER)
                .joinedAt(now)
                .build());

        return groupMapper.toMemberSummary(member, userMapper.toSummary(caller));
    }

    @Transactional
    public GroupInviteSummary declineInvite(UUID callerId, UUID inviteId) {
        GroupInvite invite = groupInviteRepository.findById(inviteId).orElseThrow(InviteNotFoundException::new);
        User caller = getCallerOrThrow(callerId);
        requireInvitee(invite, caller);
        requirePending(invite);

        invite.decline(Instant.now(clock));
        groupInviteRepository.save(invite);

        return groupMapper.toInviteSummary(invite);
    }

    @Transactional
    public GroupInviteSummary revokeInvite(UUID callerId, UUID inviteId) {
        GroupInvite invite = groupInviteRepository.findById(inviteId).orElseThrow(InviteNotFoundException::new);
        getGroupOrThrow(invite.getGroupId());
        requireAdmin(invite.getGroupId(), callerId);
        requirePending(invite);

        invite.revoke(Instant.now(clock));
        groupInviteRepository.save(invite);

        return groupMapper.toInviteSummary(invite);
    }

    public List<GroupMemberSummary> listMembers(UUID callerId, UUID groupId) {
        getGroupOrThrow(groupId);
        requireMembership(groupId, callerId);
        return memberSummaries(groupId);
    }

    @Transactional
    public void removeMember(UUID callerId, UUID groupId, UUID targetUserId) {
        getGroupOrThrow(groupId);
        requireAdmin(groupId, callerId);

        if (callerId.equals(targetUserId))
            throw new CannotRemoveSelfException();

        GroupMember target = groupMemberRepository.findByGroupIdAndUserId(groupId, targetUserId)
                .orElseThrow(NotGroupMemberException::new);

        // Unreachable through the public API in this slice: requireAdmin above
        // guarantees the caller is always counted in this same admin count, so
        // count <= 1 here implies target == caller, which CannotRemoveSelfException
        // already catches. Kept for defensiveness ahead of a future role-promotion slice.
        if (target.getRole() == GroupRole.ADMIN
                && groupMemberRepository.countByGroupIdAndRole(groupId, GroupRole.ADMIN) <= 1)
            throw new CannotRemoveLastAdminException();

        groupMemberRepository.delete(target);
    }

    @Transactional
    public void leaveGroup(UUID callerId, UUID groupId) {
        getGroupOrThrow(groupId);
        GroupMember membership = requireMembership(groupId, callerId);

        if (membership.getRole() == GroupRole.ADMIN) {
            long adminCount = groupMemberRepository.countByGroupIdAndRole(groupId, GroupRole.ADMIN);
            long totalCount = groupMemberRepository.countByGroupId(groupId);
            if (adminCount <= 1 && totalCount > 1)
                throw new CannotRemoveLastAdminException();
        }

        groupMemberRepository.delete(membership);
    }

    private List<GroupMemberSummary> memberSummaries(UUID groupId) {
        List<GroupMember> members = groupMemberRepository.findByGroupId(groupId);
        Map<UUID, User> usersById = userRepository.findAllById(
                        members.stream().map(GroupMember::getUserId).toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, Function.identity()));

        return members.stream()
                .map(member -> groupMapper.toMemberSummary(member, userMapper.toSummary(usersById.get(member.getUserId()))))
                .toList();
    }

    private Group getGroupOrThrow(UUID groupId) {
        return groupRepository.findById(groupId).orElseThrow(GroupNotFoundException::new);
    }

    private GroupMember requireMembership(UUID groupId, UUID userId) {
        return groupMemberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(NotGroupMemberException::new);
    }

    private GroupMember requireAdmin(UUID groupId, UUID userId) {
        GroupMember membership = requireMembership(groupId, userId);
        if (membership.getRole() != GroupRole.ADMIN)
            throw new InsufficientRoleException();
        return membership;
    }

    private void requirePending(GroupInvite invite) {
        if (invite.getStatus() != InviteStatus.PENDING)
            throw new InviteNotPendingException();
    }

    private void requireInvitee(GroupInvite invite, User caller) {
        if (!invite.getPhone().equals(caller.getPhone()))
            throw new InviteNotFoundException();
    }

    /**
     * Creating or joining a group needs a verified phone. Only createGroup and acceptInvite call
     * this: inviting stays open, since the invitee may not even have an account yet.
     */
    private void requireVerifiedPhone(UUID userId) {
        User user = userRepository.findById(userId)
                // A valid token for a user who no longer exists is an invalid token: 401, as on GET /me.
                .orElseThrow(() -> new InsufficientAuthenticationException("Authenticated user no longer exists"));
        if (!user.isPhoneVerified())
            throw new PhoneNotVerifiedException();
    }

    private User getCallerOrThrow(UUID callerId) {
        return userRepository.findById(callerId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    }
}
