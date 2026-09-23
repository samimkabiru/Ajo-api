package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.auth.User;
import com.theninjadev.ajoapi.auth.UserMapper;
import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.auth.UserSummary;
import com.theninjadev.ajoapi.contribution.ContributionRepository;
import com.theninjadev.ajoapi.contribution.MissingIdempotencyKeyException;
import com.theninjadev.ajoapi.contribution.RoundNotActiveException;
import com.theninjadev.ajoapi.group.*;
import com.theninjadev.ajoapi.ledger.*;
import com.theninjadev.ajoapi.payout.Payout;
import com.theninjadev.ajoapi.payout.PayoutRepository;
import com.theninjadev.ajoapi.round.*;
import com.theninjadev.ajoapi.swap.ParticipantNotActiveException;
import com.theninjadev.ajoapi.swap.PositionSwapRequestRepository;
import jakarta.persistence.EntityManager;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class ExitService {

    private final ExitRequestRepository exitRequestRepository;
    private final RepaymentRepository repaymentRepository;
    private final RoundRepository roundRepository;
    private final RoundParticipantRepository roundParticipantRepository;
    private final CycleRepository cycleRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final ExitMapper exitMapper;
    private final LedgerService ledgerService;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final PayoutRepository payoutRepository;
    private final ContributionRepository contributionRepository;
    private final Clock clock;
    private final PositionSwapRequestRepository swapRequestRepository;
    private final EntityManager entityManager;

    @Transactional
    public ExitRequestSummary requestExit(UUID callerId, UUID roundId) {

        // Phase 1 — discovery, no locks. Only ids survive past the clear().
        UUID participantId;
        UUID cycleId;
        {
            Round round = getRoundOrThrow(roundId);
            requireGroupMembership(round.getGroupId(), callerId);

            RoundParticipant participant = roundParticipantRepository
                    .findByRoundIdAndUserId(roundId, callerId)
                    .orElseThrow(RoundParticipantNotFoundException::new);

            if (participant.getStatus() != ParticipantStatus.ACTIVE)
                throw new ParticipantNotActiveException();

            if (exitRequestRepository.existsByParticipantIdAndStatus(
                    participant.getId(), ExitStatus.PENDING_SETTLEMENT))
                throw new ExitAlreadyRequestedException();

            participantId = participant.getId();
            cycleId = cycleForBeneficiary(roundId, participantId)
                        .orElseThrow(() -> new IllegalStateException("Active participant has no cycle"))
                        .getId();
        }

        entityManager.clear();

        // Phase 2 — lock the cycle, then reload everything fresh.
        Cycle cycle = cycleRepository.findAllByIdForUpdate(List.of(cycleId)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Participant has no cycle"));

        RoundParticipant participant = roundParticipantRepository.findById(participantId)
                .orElseThrow(RoundParticipantNotFoundException::new);
        Round round = getRoundOrThrow(participant.getRoundId());

        if (round.getStatus() != RoundStatus.ACTIVE)
            throw new RoundNotActiveException();

        // Re-check under the lock: another request may have landed while we waited.
        if (participant.getStatus() != ParticipantStatus.ACTIVE)
            throw new ParticipantNotActiveException();

        if (exitRequestRepository.existsByParticipantIdAndStatus(participantId, ExitStatus.PENDING_SETTLEMENT))
            throw new ExitAlreadyRequestedException();

        Instant now = Instant.now(clock);
        long exposure = exposureOf(participantId);

        ExitRequest exit = ExitRequest.builder()
                .id(UUID.randomUUID())
                .roundId(participant.getRoundId())
                .participantId(participantId)
                .exposureAtRequest(exposure)
                .status(ExitStatus.PENDING_SETTLEMENT)
                .requestedAt(now)
                .build();
        exitRequestRepository.save(exit);

        if (exposure == 0) {
            // Nothing owed in either direction — they can go now.
            completeExit(exit, participant, cycle, now);
        } else {
            // They owe (positive) or are owed (negative). Either way the cycle is untouched
            // until settlement, so a cancelled exit needs no repair.
            participant.markPendingExit();
            roundParticipantRepository.save(participant);
        }

        return toSummary(exit, participant);
    }

    @Transactional
    public RepaymentSummary repay(UUID callerId, UUID participantId,
                                  RepayRequest request, String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new MissingIdempotencyKeyException();

        // Phase 1 — discovery, no locks.
        UUID cycleId;
        {
            RoundParticipant participant = roundParticipantRepository.findById(participantId)
                    .orElseThrow(RoundParticipantNotFoundException::new);
            Round round = getRoundOrThrow(participant.getRoundId());
            requireGroupMembership(round.getGroupId(), callerId);

            Repayment existing = repaymentRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existing != null) {
                if (!existing.getParticipantId().equals(participantId))
                    throw new IdempotencyKeyReusedException();
                return toSummary(existing, participant);
            }

            // The debtor repays for themselves; an admin may record a cash repayment for them.
            if (!participant.getUserId().equals(callerId)) {
                GroupMember caller = groupMemberRepository
                        .findByGroupIdAndUserId(round.getGroupId(), callerId)
                        .orElseThrow(NotGroupMemberException::new);
                if (caller.getRole() != GroupRole.ADMIN)
                    throw new InsufficientRoleException();
            }

            cycleId = cycleForBeneficiary(participant.getRoundId(), participantId)
                    .map(Cycle::getId)
                    .orElse(null);
        }

        entityManager.clear();

        // Phase 2 — lock the cycle, then reload everything fresh.
        Cycle cycle = cycleId == null
                ? null
                : cycleRepository.findAllByIdForUpdate(List.of(cycleId)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Cycle disappeared"));

        RoundParticipant participant = roundParticipantRepository.findById(participantId)
                .orElseThrow(RoundParticipantNotFoundException::new);
        Round round = getRoundOrThrow(participant.getRoundId());

        if (round.getStatus() != RoundStatus.ACTIVE && round.getStatus() != RoundStatus.COMPLETED)
            throw new RoundNotActiveException();

        long exposure = exposureOf(participantId);
        if (exposure <= 0)
            throw new NothingToRepayException();
        if (request.amountKobo() > exposure)
            throw new RepaymentExceedsDebtException();

        LedgerAccount pool = ledgerAccountRepository
                .findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, round.getId())
                .orElseThrow(() -> new IllegalStateException("Active round has no pool account"));

        Instant now = Instant.now(clock);
        UUID repaymentId = UUID.randomUUID();

        // Money coming back in — the same two lines as a contribution.
        UUID transactionId = ledgerService.post(
                EntryType.REPAYMENT,
                repaymentId,
                List.of(
                        new PostingLine(LedgerAccounts.PLATFORM_CASH_ID, request.amountKobo()),
                        new PostingLine(pool.getId(), -request.amountKobo())));

        Repayment repayment = Repayment.builder()
                .id(repaymentId)
                .participantId(participantId)
                .amountKobo(request.amountKobo())
                .method(request.method() == null ? RepaymentMethod.ONLINE : request.method())
                .recordedBy(callerId)
                .idempotencyKey(idempotencyKey)
                .ledgerTransactionId(transactionId)
                .createdAt(now)
                .build();

        try {
            repaymentRepository.saveAndFlush(repayment);
        } catch (DataIntegrityViolationException e) {
            // Another request with this key committed between our check above and this insert.
            // Roll back — our ledger posting must not survive.
            throw new IdempotencyKeyReusedException();
        }

        // Debt cleared: if they were on their way out, they can go now.
        if (exposure - request.amountKobo() == 0) {
            exitRequestRepository
                    .findByParticipantIdAndStatus(participantId, ExitStatus.PENDING_SETTLEMENT)
                    .ifPresent(exit -> completeExit(exit, participant, cycle, now));
        }

        return toSummary(repayment, participant);
    }

    @Transactional
    public ExitRequestSummary cancelExit(UUID callerId, UUID exitId) {
        ExitRequest exit = exitRequestRepository.findById(exitId)
                .orElseThrow(ExitRequestNotFoundException::new);
        RoundParticipant participant = roundParticipantRepository.findById(exit.getParticipantId())
                .orElseThrow(() -> new IllegalStateException("Exiting participant not found"));

        if (!participant.getUserId().equals(callerId))
            throw new NotExitingParticipantException();

        if (exit.getStatus() != ExitStatus.PENDING_SETTLEMENT)
            throw new ExitNotPendingSettlementException();

        exit.cancel(Instant.now(clock));
        exitRequestRepository.save(exit);

        participant.markActive();
        roundParticipantRepository.save(participant);

        return exitRequestSummaries(List.of(exit)).getFirst();
    }

    public List<ExitRequestSummary> listExitsForRound(UUID callerId, UUID roundId) {
        Round round = getRoundOrThrow(roundId);
        requireGroupMembership(round.getGroupId(), callerId);

        return exitRequestSummaries(exitRequestRepository.findByRoundId(roundId));
    }

    public ExitRequestSummary getMyExit(UUID callerId, UUID roundId) {
        getRoundOrThrow(roundId);
        RoundParticipant participant = roundParticipantRepository.findByRoundIdAndUserId(roundId, callerId)
                .orElseThrow(RoundParticipantNotFoundException::new);

        ExitRequest exit = exitRequestRepository
                .findByParticipantIdAndStatus(participant.getId(), ExitStatus.PENDING_SETTLEMENT)
                .orElseThrow(ExitRequestNotFoundException::new);

        return exitRequestSummaries(List.of(exit)).getFirst();
    }

    public List<RepaymentSummary> listRepayments(UUID callerId, UUID participantId) {
        requireVisibleParticipant(callerId, participantId);

        return repaymentSummaries(repaymentRepository.findByParticipantId(participantId));
    }

    public ExposureSummary getExposure(UUID callerId, UUID participantId) {
        requireVisibleParticipant(callerId, participantId);

        long exposure = exposureOf(participantId);
        return new ExposureSummary(participantId, exposure, exposure > 0, exposure < 0);
    }

    private Optional<Cycle> cycleForBeneficiary(UUID roundId, UUID participantId) {
        return cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId).stream()
                .filter(c -> participantId.equals(c.getBeneficiaryId()))
                .findFirst();
    }

    private ExitRequestSummary toSummary(ExitRequest exit, RoundParticipant participant) {
        User user = userRepository.findById(participant.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found"));
        return exitMapper.toExitRequestSummary(exit, userMapper.toSummary(user));
    }

    private RepaymentSummary toSummary(Repayment repayment, RoundParticipant participant) {
        User user = userRepository.findById(participant.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found"));
        return exitMapper.toRepaymentSummary(repayment, userMapper.toSummary(user));
    }

    private void completeExit(ExitRequest exit, RoundParticipant participant, Cycle lockedCycle, Instant now) {
        participant.markExited();
        exit.complete(now);

        // Null when the participant's cycle was already vacated — nothing left to vacate.
        if (lockedCycle != null && lockedCycle.getStatus() != CycleStatus.PAID) {
            lockedCycle.markVacant();
            cycleRepository.save(lockedCycle);
        }

        roundParticipantRepository.save(participant);
        exitRequestRepository.save(exit);

        var staleSwaps = swapRequestRepository.findPendingInvolvingForUpdate(List.of(participant.getId()));
        staleSwaps.forEach(s -> s.supersede(now));
        swapRequestRepository.saveAll(staleSwaps);
    }

    long exposureOf(UUID participantId) {
        long collected = payoutRepository.findByParticipantId(participantId).stream()
                .mapToLong(Payout::getActualAmountKobo)
                .sum();

        long contributed = contributionRepository.sumAmountKoboByParticipantId(participantId);
        long repaid = repaymentRepository.sumAmountKoboByParticipantId(participantId);

        return collected - contributed - repaid;
    }

    private List<ExitRequestSummary> exitRequestSummaries(List<ExitRequest> exits) {
        if (exits.isEmpty())
            return List.of();

        Map<UUID, UserSummary> usersByParticipantId =
                userSummariesByParticipantId(exits.stream().map(ExitRequest::getParticipantId).toList());

        return exits.stream()
                .map(exit -> exitMapper.toExitRequestSummary(
                        exit, usersByParticipantId.get(exit.getParticipantId())))
                .toList();
    }

    private List<RepaymentSummary> repaymentSummaries(List<Repayment> repayments) {
        if (repayments.isEmpty())
            return List.of();

        Map<UUID, UserSummary> usersByParticipantId =
                userSummariesByParticipantId(repayments.stream().map(Repayment::getParticipantId).toList());

        return repayments.stream()
                .map(repayment -> exitMapper.toRepaymentSummary(
                        repayment, usersByParticipantId.get(repayment.getParticipantId())))
                .toList();
    }

    /** Two queries regardless of row count: participants, then their users. */
    private Map<UUID, UserSummary> userSummariesByParticipantId(Collection<UUID> participantIds) {
        List<RoundParticipant> participants = roundParticipantRepository.findAllById(participantIds);

        Map<UUID, User> usersById = userRepository.findAllById(
                        participants.stream().map(RoundParticipant::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return participants.stream()
                .collect(Collectors.toMap(
                        RoundParticipant::getId,
                        p -> userMapper.toSummary(usersById.get(p.getUserId()))));
    }

    private RoundParticipant requireVisibleParticipant(UUID callerId, UUID participantId) {
        RoundParticipant participant = roundParticipantRepository.findById(participantId)
                .orElseThrow(RoundParticipantNotFoundException::new);
        Round round = getRoundOrThrow(participant.getRoundId());
        requireGroupMembership(round.getGroupId(), callerId);
        return participant;
    }

    private Round getRoundOrThrow(UUID roundId) {
        return roundRepository.findById(roundId).orElseThrow(RoundNotFoundException::new);
    }

    private GroupMember requireGroupMembership(UUID groupId, UUID userId) {
        return groupMemberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(NotGroupMemberException::new);
    }
}
