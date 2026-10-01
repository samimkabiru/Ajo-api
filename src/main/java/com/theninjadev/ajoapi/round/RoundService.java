package com.theninjadev.ajoapi.round;

import com.theninjadev.ajoapi.auth.User;
import com.theninjadev.ajoapi.auth.UserMapper;
import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.auth.UserSummary;
import com.theninjadev.ajoapi.group.GroupMember;
import com.theninjadev.ajoapi.group.Group;
import com.theninjadev.ajoapi.group.GroupArchivedException;
import com.theninjadev.ajoapi.group.GroupMemberRepository;
import com.theninjadev.ajoapi.group.GroupNotFoundException;
import com.theninjadev.ajoapi.group.GroupRepository;
import com.theninjadev.ajoapi.group.GroupRole;
import com.theninjadev.ajoapi.group.InsufficientRoleException;
import com.theninjadev.ajoapi.group.NotGroupMemberException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.theninjadev.ajoapi.ledger.AccountType;
import com.theninjadev.ajoapi.ledger.LedgerAccount;
import com.theninjadev.ajoapi.ledger.LedgerAccountRepository;
import jakarta.persistence.EntityManager;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class RoundService {

    private final RoundRepository roundRepository;
    private final RoundParticipantRepository roundParticipantRepository;
    private final CycleRepository cycleRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final RoundMapper roundMapper;
    private final Clock clock;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final GroupRepository groupRepository;
    private final EntityManager entityManager;

    @Transactional
    public RoundSummary createRound(UUID callerId, UUID groupId, CreateRoundRequest request) {
        requireGroupAdmin(groupId, callerId);
        Group group = groupRepository.findByIdForUpdate(groupId).orElseThrow(GroupNotFoundException::new);
        if (group.isArchived())
            throw new GroupArchivedException();

        if (roundRepository.existsByGroupIdAndStatusIn(groupId, List.of(RoundStatus.FORMING, RoundStatus.ACTIVE)))
            throw new GroupHasActiveRoundException();

        Instant now = Instant.now(clock);

        Round round = roundRepository.save(Round.builder()
                .id(UUID.randomUUID())
                .groupId(groupId)
                .contributionAmountKobo(request.contributionAmountKobo())
                .status(RoundStatus.FORMING)
                .createdBy(callerId)
                .firstPayoutDate(request.firstPayoutDate())
                .createdAt(now)
                .updatedAt(now)
                .build());

        return roundMapper.toSummary(round);
    }

    public List<RoundSummary> listRoundsForGroup(UUID callerId, UUID groupId) {
        requireGroupMembership(groupId, callerId);
        return roundRepository.findByGroupId(groupId).stream()
                .map(roundMapper::toSummary)
                .toList();
    }

    public RoundDetail getRound(UUID callerId, UUID roundId) {
        Round round = getRoundOrThrow(roundId);
        requireGroupMembership(round.getGroupId(), callerId);

        List<ParticipantSummary> participants = participantSummaries(roundId);
        List<CycleSummary> cycles = cycleSummaries(roundId);

        return new RoundDetail(round.getId(), round.getGroupId(), round.getContributionAmountKobo(),
                round.getStatus(), round.getCreatedBy(), round.getActivatedAt(), round.getFirstPayoutDate(),
                round.getCreatedAt(), round.getUpdatedAt(), participants, cycles);
    }

    @Transactional
    public RoundSummary updateRound(UUID callerId, UUID roundId, UpdateRoundRequest request) {
        Round round = getRoundOrThrow(roundId);
        requireGroupAdmin(round.getGroupId(), callerId);
        requireForming(round);

        round.updateTerms(request.contributionAmountKobo(), request.firstPayoutDate(), Instant.now(clock));
        roundRepository.save(round);

        return roundMapper.toSummary(round);
    }

    @Transactional
    public ParticipantSummary addParticipant(UUID callerId, UUID roundId, AddParticipantRequest request) {
        Round round = getRoundOrThrow(roundId);
        requireGroupAdmin(round.getGroupId(), callerId);
        requireForming(round);

        if (!groupMemberRepository.existsByGroupIdAndUserId(round.getGroupId(), request.userId()))
            throw new UserNotGroupMemberException();

        if (roundParticipantRepository.existsByRoundIdAndUserId(roundId, request.userId()))
            throw new AlreadyRoundParticipantException();

        User user = getUserOrThrow(request.userId());

        RoundParticipant participant = roundParticipantRepository.save(RoundParticipant.builder()
                .id(UUID.randomUUID())
                .roundId(roundId)
                .userId(request.userId())
                .status(ParticipantStatus.ACTIVE)
                .joinedAt(Instant.now(clock))
                .build());

        return roundMapper.toParticipantSummary(participant, userMapper.toSummary(user));
    }

    @Transactional
    public void removeParticipant(UUID callerId, UUID roundId, UUID targetUserId) {
        Round round = getRoundOrThrow(roundId);
        requireGroupAdmin(round.getGroupId(), callerId);
        requireForming(round);

        RoundParticipant participant = roundParticipantRepository.findByRoundIdAndUserId(roundId, targetUserId)
                .orElseThrow(RoundParticipantNotFoundException::new);

        roundParticipantRepository.delete(participant);
    }

    @Transactional
    public ParticipantSummary joinRound(UUID callerId, UUID roundId) {
        Round round = getRoundOrThrow(roundId);
        requireGroupMembership(round.getGroupId(), callerId);
        requireForming(round);

        if (roundParticipantRepository.existsByRoundIdAndUserId(roundId, callerId))
            throw new AlreadyRoundParticipantException();

        User caller = getUserOrThrow(callerId);

        RoundParticipant participant = roundParticipantRepository.save(RoundParticipant.builder()
                .id(UUID.randomUUID())
                .roundId(roundId)
                .userId(callerId)
                .status(ParticipantStatus.ACTIVE)
                .joinedAt(Instant.now(clock))
                .build());

        return roundMapper.toParticipantSummary(participant, userMapper.toSummary(caller));
    }

    @Transactional
    public void leaveRound(UUID callerId, UUID roundId) {
        Round round = getRoundOrThrow(roundId);

        RoundParticipant participant = roundParticipantRepository.findByRoundIdAndUserId(roundId, callerId)
                .orElseThrow(RoundParticipantNotFoundException::new);

        requireForming(round);

        roundParticipantRepository.delete(participant);
    }

    @Transactional
    public RoundSummary cancelRound(UUID callerId, UUID roundId) {
        Round round = getRoundOrThrow(roundId);
        requireGroupAdmin(round.getGroupId(), callerId);
        requireForming(round);

        round.cancel(Instant.now(clock));
        roundRepository.save(round);

        return roundMapper.toSummary(round);
    }

    @Transactional
    public RoundDetail activate(UUID callerId, UUID roundId) {
        Round round = getRoundOrThrow(roundId);
        requireGroupAdmin(round.getGroupId(), callerId);

        // The group row lock serialises activation against GroupService.deleteGroup. A group
        // deleted while we waited took this round with it. Otherwise re-read the round, since
        // it was loaded before the lock.
        Group group = groupRepository.findByIdForUpdate(round.getGroupId())
                .orElseThrow(RoundNotFoundException::new);
        entityManager.refresh(round);
        if (group.isArchived())
            throw new GroupArchivedException();
        requireForming(round);

        if (round.getFirstPayoutDate() == null)
            throw new RoundNotReadyException();

        List<RoundParticipant> participants = roundParticipantRepository.findByRoundId(roundId);

        if (participants.size() < 2)
            throw new InsufficientParticipantsException();
        Instant now = Instant.now(clock);

        var ordered = assignPositions(round.getGroupId(), participants);

        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).assignPosition(i + 1);
        }
        roundParticipantRepository.saveAll(ordered);

        List<Cycle> cycles = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            LocalDate payoutOn = round.getFirstPayoutDate().plusMonths(i);

            cycles.add(Cycle.builder()
                            .id(UUID.randomUUID())
                            .roundId(roundId)
                            .cycleNumber(i + 1)
                            .beneficiaryId(ordered.get(i).getId())
                            .payoutOn(payoutOn)
                            .opensOn(payoutOn.withDayOfMonth(1))
                            .dueOn(payoutOn)
                            .status(CycleStatus.SCHEDULED)
                            .createdAt(now)
                    .build());
        }
        cycleRepository.saveAll(cycles);

        LedgerAccount roundPoolAccount = LedgerAccount.builder()
                .id(UUID.randomUUID())
                .accountType(AccountType.ROUND_POOL)
                .ownerId(roundId)
                .currency("NGN")
                .createdAt(now)
                .build();
        ledgerAccountRepository.save(roundPoolAccount);

        List<LedgerAccount> accounts = new ArrayList<>();
        ordered.forEach(participant -> {
            accounts.add(LedgerAccount.builder()
                    .id(UUID.randomUUID())
                    .accountType(AccountType.PARTICIPANT)
                    .ownerId(participant.getId())
                    .currency("NGN")
                    .createdAt(now)
                    .build());
        });
        ledgerAccountRepository.saveAll(accounts);

        round.activate(now);
        roundRepository.save(round);

        return getRound(callerId, roundId);
    }

    private List<RoundParticipant> assignPositions(UUID groupId, List<RoundParticipant> participants) {
        Set<UUID> veteranIds = roundParticipantRepository
                .findUserIdsWithCompletedRoundsInGroup(groupId,
                        participants.stream().map(RoundParticipant::getUserId).toList());

        List<RoundParticipant> veterans = new ArrayList<>(
                participants.stream().filter(p -> veteranIds.contains(p.getUserId())).toList());
        List<RoundParticipant> newcomers = new ArrayList<>(
                participants.stream().filter(p -> !veteranIds.contains(p.getUserId())).toList());

        Collections.shuffle(veterans);
        Collections.shuffle(newcomers);

        List<RoundParticipant> ordered = new ArrayList<>(veterans);
        ordered.addAll(newcomers);
        return ordered;
    }

    private List<ParticipantSummary> participantSummaries(UUID roundId) {
        List<RoundParticipant> participants = roundParticipantRepository.findByRoundId(roundId);
        Map<UUID, User> usersById = userRepository.findAllById(
                        participants.stream().map(RoundParticipant::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return participants.stream()
                .map(participant -> roundMapper.toParticipantSummary(
                        participant, userMapper.toSummary(usersById.get(participant.getUserId()))))
                .toList();
    }

    private List<CycleSummary> cycleSummaries(UUID roundId) {
        List<Cycle> cycles = cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId);
        if (cycles.isEmpty())
            return List.of();

        List<UUID> beneficiaryParticipantIds = cycles.stream()
                .map(Cycle::getBeneficiaryId)
                .filter(Objects::nonNull)
                .toList();

        Map<UUID, RoundParticipant> participantsById = roundParticipantRepository.findAllById(beneficiaryParticipantIds).stream()
                .collect(Collectors.toMap(RoundParticipant::getId, Function.identity()));

        Map<UUID, User> usersById = userRepository.findAllById(
                        participantsById.values().stream().map(RoundParticipant::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return cycles.stream()
                .map(cycle -> roundMapper.toCycleSummary(cycle, beneficiarySummary(cycle, participantsById, usersById)))
                .toList();
    }

    private UserSummary beneficiarySummary(
            Cycle cycle, Map<UUID, RoundParticipant> participantsById, Map<UUID, User> usersById) {
        if (cycle.getBeneficiaryId() == null)
            return null;

        RoundParticipant participant = participantsById.get(cycle.getBeneficiaryId());
        if (participant == null)
            return null;

        return userMapper.toSummary(usersById.get(participant.getUserId()));
    }

    private Round getRoundOrThrow(UUID roundId) {
        return roundRepository.findById(roundId).orElseThrow(RoundNotFoundException::new);
    }

    private void requireForming(Round round) {
        if (round.getStatus() != RoundStatus.FORMING)
            throw new RoundNotFormingException();
    }

    private GroupMember requireGroupMembership(UUID groupId, UUID userId) {
        return groupMemberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(NotGroupMemberException::new);
    }

    private GroupMember requireGroupAdmin(UUID groupId, UUID userId) {
        GroupMember membership = requireGroupMembership(groupId, userId);
        if (membership.getRole() != GroupRole.ADMIN)
            throw new InsufficientRoleException();
        return membership;
    }

    private User getUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
