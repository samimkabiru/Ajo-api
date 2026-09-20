package com.theninjadev.ajoapi.contribution;

import com.theninjadev.ajoapi.auth.User;
import com.theninjadev.ajoapi.auth.UserMapper;
import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.auth.UserSummary;
import com.theninjadev.ajoapi.group.*;
import com.theninjadev.ajoapi.ledger.*;
import com.theninjadev.ajoapi.round.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class ContributionService {

    private final ContributionRepository contributionRepository;
    private final CycleRepository cycleRepository;
    private final RoundRepository roundRepository;
    private final RoundParticipantRepository roundParticipantRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final ContributionMapper contributionMapper;
    private final LedgerService ledgerService;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final Clock clock;

    public List<ContributionSummary> listForCycle(UUID callerId, UUID cycleId) {
        Cycle cycle = cycleRepository.findById(cycleId).orElseThrow(CycleNotFoundException::new);
        Round round = getRoundOrThrow(cycle.getRoundId());
        requireGroupMembership(round.getGroupId(), callerId);

        return contributionSummaries(contributionRepository.findByCycleId(cycleId));
    }

    public List<ContributionSummary> listForParticipant(UUID callerId, UUID participantId) {
        RoundParticipant participant = roundParticipantRepository.findById(participantId)
                .orElseThrow(RoundParticipantNotFoundException::new);
        Round round = getRoundOrThrow(participant.getRoundId());
        requireGroupMembership(round.getGroupId(), callerId);

        return contributionSummaries(contributionRepository.findByParticipantId(participantId));
    }

    public List<ContributionSummary> listForRound(UUID callerId, UUID roundId) {
        Round round = getRoundOrThrow(roundId);
        requireGroupMembership(round.getGroupId(), callerId);

        List<UUID> cycleIds = cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId).stream()
                .map(Cycle::getId)
                .toList();

        if (cycleIds.isEmpty())
            return List.of();

        return contributionSummaries(contributionRepository.findByCycleIdIn(cycleIds));
    }

    public PoolBalance poolBalance(UUID callerId, UUID roundId) {
        Round round = getRoundOrThrow(roundId);
        requireGroupMembership(round.getGroupId(), callerId);

        LedgerAccount poolAccount = ledgerAccountRepository
                .findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, roundId)
                .orElseThrow(RoundNotActiveException::new);

        return new PoolBalance(roundId, ledgerService.balanceOf(poolAccount.getId()));
    }

    @Transactional
    public ContributionSummary contribute(UUID callerId, UUID cycleId,
                                          ContributeRequest request, String idempotencyKey) {
        if (idempotencyKey == null)
            throw new MissingIdempotencyKeyException();

        Cycle cycle = cycleRepository.findById(cycleId).orElseThrow(CycleNotFoundException::new);
        Round round = getRoundOrThrow(cycle.getRoundId());

        requireGroupMembership(round.getGroupId(), callerId);

        if (round.getStatus() != RoundStatus.ACTIVE)
            throw new RoundNotActiveException();

        Contribution existingContribution = contributionRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existingContribution != null) {
            RoundParticipant targetParticipant = roundParticipantRepository
                    .findById(existingContribution.getParticipantId())
                    .orElseThrow(() -> new IllegalStateException("Participant not found"));

            User user = userRepository
                    .findById(targetParticipant.getUserId())
                    .orElseThrow(() -> new IllegalStateException("User not found"));

            return contributionMapper.toSummary(existingContribution, userMapper.toSummary(user));
        }


        UUID targetUserId;
        if (request.userId() == null || request.userId().equals(callerId)) {
            targetUserId = callerId;
        } else {
            GroupMember caller = groupMemberRepository
                    .findByGroupIdAndUserId(round.getGroupId(), callerId)
                    .orElseThrow(NotGroupMemberException::new);
            if (caller.getRole() != GroupRole.ADMIN)
                throw new InsufficientRoleException();
            targetUserId = request.userId();
        }

        RoundParticipant targetParticipant = roundParticipantRepository
                .findByRoundIdAndUserId(round.getId(), targetUserId)
                .orElseThrow(RoundParticipantNotFoundException::new);

        if (!targetParticipant.getRoundId().equals(cycle.getRoundId()))
            throw new ParticipantNotInRoundException();

        if (LocalDate.now(clock).isBefore(cycle.getOpensOn()))
            throw new CycleNotOpenException();

        if (cycle.getStatus() == CycleStatus.PAID)
            throw new CycleAlreadyPaidException();

        if (request.amountKobo() != round.getContributionAmountKobo())
            throw new IncorrectContributionAmountException();

        if (contributionRepository.existsByCycleIdAndParticipantId(cycleId, targetParticipant.getId()))
            throw new DuplicateContributionException();

        LedgerAccount pool = ledgerAccountRepository
                .findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, round.getId())
                .orElseThrow(() -> new IllegalStateException("Active round has no pool account"));

        if (cycle.getStatus() == CycleStatus.SCHEDULED) {
            cycle.open();
            cycleRepository.save(cycle);
        }

        UUID contributionId = UUID.randomUUID();

        UUID transactionId = ledgerService.post(
                EntryType.CONTRIBUTION,
                contributionId,
                List.of(
                        new PostingLine(LedgerAccounts.PLATFORM_CASH_ID, request.amountKobo()),
                        new PostingLine(pool.getId(), -request.amountKobo())));

        Contribution contribution = Contribution.builder()
                .id(contributionId)
                .cycleId(cycleId)
                .participantId(targetParticipant.getId())
                .amountKobo(request.amountKobo())
                .method(request.method() == null ? ContributionMethod.ONLINE : request.method())
                .recordedBy(callerId)
                .idempotencyKey(idempotencyKey)
                .ledgerTransactionId(transactionId)
                .createdAt(Instant.now(clock))
                .build();

        try {
            contributionRepository.saveAndFlush(contribution);
        } catch (DataIntegrityViolationException e) {
            // Another request with this idempotency key committed between our check
            // above and this insert. Roll back — our ledger posting must not survive.
            throw new DuplicateContributionException();
        }

        User user = userRepository.findById(targetParticipant.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found"));

        return contributionMapper.toSummary(contribution, userMapper.toSummary(user));
    }

    private List<ContributionSummary> contributionSummaries(List<Contribution> contributions) {
        if (contributions.isEmpty())
            return List.of();

        Map<UUID, RoundParticipant> participantsById = roundParticipantRepository.findAllById(
                        contributions.stream().map(Contribution::getParticipantId).toList())
                .stream()
                .collect(Collectors.toMap(RoundParticipant::getId, Function.identity()));

        Map<UUID, User> usersById = userRepository.findAllById(
                        participantsById.values().stream().map(RoundParticipant::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return contributions.stream()
                .map(contribution -> contributionMapper.toSummary(
                        contribution, participantSummary(contribution, participantsById, usersById)))
                .toList();
    }

    private UserSummary participantSummary(Contribution contribution,
            Map<UUID, RoundParticipant> participantsById, Map<UUID, User> usersById) {
        RoundParticipant participant = participantsById.get(contribution.getParticipantId());
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
