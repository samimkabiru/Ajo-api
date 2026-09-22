package com.theninjadev.ajoapi.swap;

import com.theninjadev.ajoapi.auth.User;
import com.theninjadev.ajoapi.auth.UserMapper;
import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.auth.UserSummary;
import com.theninjadev.ajoapi.contribution.RoundNotActiveException;
import com.theninjadev.ajoapi.group.GroupMember;
import com.theninjadev.ajoapi.group.GroupMemberRepository;
import com.theninjadev.ajoapi.group.NotGroupMemberException;
import com.theninjadev.ajoapi.round.Cycle;
import com.theninjadev.ajoapi.round.CycleRepository;
import com.theninjadev.ajoapi.round.CycleStatus;
import com.theninjadev.ajoapi.round.ParticipantStatus;
import com.theninjadev.ajoapi.round.Round;
import com.theninjadev.ajoapi.round.RoundParticipant;
import com.theninjadev.ajoapi.round.RoundParticipantNotFoundException;
import com.theninjadev.ajoapi.round.RoundParticipantRepository;
import com.theninjadev.ajoapi.round.RoundNotFoundException;
import com.theninjadev.ajoapi.round.RoundRepository;
import com.theninjadev.ajoapi.round.RoundStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class SwapService {

    private final PositionSwapRequestRepository positionSwapRequestRepository;
    private final RoundRepository roundRepository;
    private final RoundParticipantRepository roundParticipantRepository;
    private final CycleRepository cycleRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final SwapMapper swapMapper;
    private final Clock clock;

    @Transactional
    public SwapRequestSummary requestSwap(UUID callerId, UUID roundId, CreateSwapRequest request) {
        Round round = getRoundOrThrow(roundId);
        requireGroupMembership(round.getGroupId(), callerId);

        if (round.getStatus() != RoundStatus.ACTIVE)
            throw new RoundNotActiveException();

        RoundParticipant caller = activeParticipantOrThrow(roundId, callerId);
        RoundParticipant target = activeParticipantOrThrow(roundId, request.targetUserId());

        if (caller.getId().equals(target.getId()))
            throw new CannotSwapWithSelfException();

        Cycle callerCycle = cycleForBeneficiary(roundId, caller.getId());
        if (callerCycle.getStatus() == CycleStatus.PAID)
            throw new RequesterAlreadyPaidOutException();

        Cycle targetCycle = cycleForBeneficiary(roundId, target.getId());
        if (targetCycle.getStatus() == CycleStatus.PAID)
            throw new TargetAlreadyPaidOutException();

        if (!positionSwapRequestRepository
                .findByRoundIdAndRequesterParticipantIdAndStatus(roundId, caller.getId(), SwapStatus.PENDING)
                .isEmpty())
            throw new OutgoingSwapAlreadyPendingException();

        Set<UUID> veterans = roundParticipantRepository.findUserIdsWithCompletedRoundsInGroup(
                round.getGroupId(), Set.of(callerId, request.targetUserId()));
        boolean callerVeteran = veterans.contains(callerId);
        boolean targetVeteran = veterans.contains(request.targetUserId());

        if (violatesVeteranPrecedence(callerVeteran, targetVeteran, caller.getPayoutPosition(), target.getPayoutPosition()))
            throw new NewcomerCannotMoveAheadOfVeteranException();

        PositionSwapRequest swap = PositionSwapRequest.builder()
                .id(UUID.randomUUID())
                .roundId(roundId)
                .requesterParticipantId(caller.getId())
                .targetParticipantId(target.getId())
                .requesterPosition(caller.getPayoutPosition())
                .targetPosition(target.getPayoutPosition())
                .status(SwapStatus.PENDING)
                .createdAt(Instant.now(clock))
                .build();

        try {
            positionSwapRequestRepository.saveAndFlush(swap);
        } catch (DataIntegrityViolationException e) {
            throw new OutgoingSwapAlreadyPendingException();
        }

        return swapRequestSummaries(List.of(swap)).getFirst();
    }

    @Transactional
    public SwapRequestSummary decline(UUID callerId, UUID swapId) {
        PositionSwapRequest swap = pendingSwapOrThrow(swapId);
        RoundParticipant target = participantOrThrow(swap.getTargetParticipantId());

        if (!target.getUserId().equals(callerId))
            throw new NotSwapTargetException();

        swap.decline(Instant.now(clock));
        positionSwapRequestRepository.save(swap);

        return swapRequestSummaries(List.of(swap)).getFirst();
    }

    @Transactional
    public SwapRequestSummary cancel(UUID callerId, UUID swapId) {
        PositionSwapRequest swap = pendingSwapOrThrow(swapId);
        RoundParticipant requester = participantOrThrow(swap.getRequesterParticipantId());

        if (!requester.getUserId().equals(callerId))
            throw new NotSwapRequesterException();

        swap.cancel(Instant.now(clock));
        positionSwapRequestRepository.save(swap);

        return swapRequestSummaries(List.of(swap)).getFirst();
    }

    public List<SwapRequestSummary> listForRound(UUID callerId, UUID roundId) {
        Round round = getRoundOrThrow(roundId);
        requireGroupMembership(round.getGroupId(), callerId);

        return swapRequestSummaries(positionSwapRequestRepository.findByRoundId(roundId));
    }

    public List<SwapRequestSummary> listMyIncoming(UUID callerId, UUID roundId) {
        getRoundOrThrow(roundId);
        RoundParticipant caller = roundParticipantRepository.findByRoundIdAndUserId(roundId, callerId)
                .orElseThrow(RoundParticipantNotFoundException::new);

        return swapRequestSummaries(positionSwapRequestRepository
                .findByRoundIdAndTargetParticipantIdAndStatus(roundId, caller.getId(), SwapStatus.PENDING));
    }

    public List<SwapRequestSummary> listMyOutgoing(UUID callerId, UUID roundId) {
        getRoundOrThrow(roundId);
        RoundParticipant caller = roundParticipantRepository.findByRoundIdAndUserId(roundId, callerId)
                .orElseThrow(RoundParticipantNotFoundException::new);

        return swapRequestSummaries(positionSwapRequestRepository
                .findByRoundIdAndRequesterParticipantIdAndStatus(roundId, caller.getId(), SwapStatus.PENDING));
    }

    @Transactional
    public SwapRequestSummary accept(UUID callerId, UUID swapId) {
        // TODO: implemented by hand
        throw new UnsupportedOperationException();
    }

    boolean violatesVeteranPrecedence(boolean callerVeteran, boolean targetVeteran,
                                       int callerPosition, int targetPosition) {
        if (callerVeteran == targetVeteran)
            return false;

        int veteranPosition = callerVeteran ? callerPosition : targetPosition;
        int newcomerPosition = callerVeteran ? targetPosition : callerPosition;

        return newcomerPosition > veteranPosition;
    }

    private RoundParticipant activeParticipantOrThrow(UUID roundId, UUID userId) {
        RoundParticipant participant = roundParticipantRepository.findByRoundIdAndUserId(roundId, userId)
                .orElseThrow(RoundParticipantNotFoundException::new);
        if (participant.getStatus() != ParticipantStatus.ACTIVE)
            throw new ParticipantNotActiveException();
        return participant;
    }

    private Cycle cycleForBeneficiary(UUID roundId, UUID participantId) {
        return cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId).stream()
                .filter(c -> participantId.equals(c.getBeneficiaryId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Active participant has no cycle in this round"));
    }

    private PositionSwapRequest pendingSwapOrThrow(UUID swapId) {
        PositionSwapRequest swap = positionSwapRequestRepository.findById(swapId)
                .orElseThrow(SwapRequestNotFoundException::new);
        if (swap.getStatus() != SwapStatus.PENDING)
            throw new SwapRequestNotPendingException();
        return swap;
    }

    private RoundParticipant participantOrThrow(UUID participantId) {
        return roundParticipantRepository.findById(participantId)
                .orElseThrow(() -> new IllegalStateException("Swap participant not found"));
    }

    private List<SwapRequestSummary> swapRequestSummaries(List<PositionSwapRequest> swaps) {
        if (swaps.isEmpty())
            return List.of();

        Set<UUID> participantIds = new HashSet<>();
        swaps.forEach(s -> {
            participantIds.add(s.getRequesterParticipantId());
            participantIds.add(s.getTargetParticipantId());
        });

        Map<UUID, RoundParticipant> participantsById = roundParticipantRepository.findAllById(participantIds).stream()
                .collect(Collectors.toMap(RoundParticipant::getId, Function.identity()));

        Map<UUID, User> usersById = userRepository.findAllById(
                        participantsById.values().stream().map(RoundParticipant::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return swaps.stream()
                .map(s -> swapMapper.toSummary(s,
                        userSummaryFor(s.getRequesterParticipantId(), participantsById, usersById),
                        userSummaryFor(s.getTargetParticipantId(), participantsById, usersById)))
                .toList();
    }

    private UserSummary userSummaryFor(UUID participantId,
            Map<UUID, RoundParticipant> participantsById, Map<UUID, User> usersById) {
        RoundParticipant participant = participantsById.get(participantId);
        if (participant == null)
            return null;
        return userMapper.toSummary(usersById.get(participant.getUserId()));
    }

    private Round getRoundOrThrow(UUID roundId) {
        return roundRepository.findById(roundId).orElseThrow(RoundNotFoundException::new);
    }

    private GroupMember requireGroupMembership(UUID groupId, UUID userId) {
        return groupMemberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(NotGroupMemberException::new);
    }
}
