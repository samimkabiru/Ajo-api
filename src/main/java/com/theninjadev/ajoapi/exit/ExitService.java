package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.auth.User;
import com.theninjadev.ajoapi.auth.UserMapper;
import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.auth.UserSummary;
import com.theninjadev.ajoapi.contribution.ContributionRepository;
import com.theninjadev.ajoapi.contribution.CycleNotFoundException;
import com.theninjadev.ajoapi.contribution.MissingIdempotencyKeyException;
import com.theninjadev.ajoapi.contribution.RoundNotActiveException;
import com.theninjadev.ajoapi.group.*;
import com.theninjadev.ajoapi.ledger.*;
import com.theninjadev.ajoapi.payout.*;
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
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class ExitService {

    private final ExitRequestRepository exitRequestRepository;
    private final RepaymentRepository repaymentRepository;
    private final BuyInRepository buyInRepository;
    private final RefundRepository refundRepository;
    private final ShortfallClaimRepository shortfallClaimRepository;
    private final PayoutMapper payoutMapper;
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
    private final ShortfallDistributor shortfallDistributor;
    private final ShortfallSettlementRepository shortfallSettlementRepository;

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
    public BuyInSummary buyIn(UUID callerId, UUID exitId, BuyInRequest request, String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new MissingIdempotencyKeyException();

        // Phase 1 — discovery, no locks. Only ids survive past the clear().
        UUID participantId;
        UUID cycleId;
        UUID roundId;
        UUID leaverUserId;
        UUID replacementUserId = request.replacementUserId();
        {
            ExitRequest discovered = exitRequestRepository.findById(exitId)
                    .orElseThrow(ExitRequestNotFoundException::new);
            Round round = getRoundOrThrow(discovered.getRoundId());
            requireGroupMembership(round.getGroupId(), callerId);

            BuyIn existing = buyInRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existing != null) {
                if (!existing.getExitRequestId().equals(exitId))
                    throw new IdempotencyKeyReusedException();
                return buyInSummaries(List.of(existing)).getFirst();
            }

            // The replacement buys in for themselves; an admin may record it for them.
            // The leaver cannot — they are the one being paid out.
            if (!replacementUserId.equals(callerId)) {
                GroupMember caller = groupMemberRepository
                        .findByGroupIdAndUserId(round.getGroupId(), callerId)
                        .orElseThrow(NotGroupMemberException::new);
                if (caller.getRole() != GroupRole.ADMIN)
                    throw new InsufficientRoleException();
            }

            if (!groupMemberRepository.existsByGroupIdAndUserId(round.getGroupId(), replacementUserId))
                throw new UserNotGroupMemberException();

            if (roundParticipantRepository.existsByRoundIdAndUserId(round.getId(), replacementUserId))
                throw new AlreadyRoundParticipantException();

            RoundParticipant slot = roundParticipantRepository.findById(discovered.getParticipantId())
                    .orElseThrow(() -> new IllegalStateException("Exiting participant not found"));

            participantId = slot.getId();
            roundId = round.getId();
            leaverUserId = slot.getUserId();          // captured before the transfer — the only record of who left
            cycleId = cycleForBeneficiary(roundId, participantId)
                    .orElseThrow(NothingToBuyIntoException::new)
                    .getId();
        }

        entityManager.clear();

        // Phase 2 — lock the slot's cycle, then reload everything fresh.
        Cycle cycle = cycleRepository.findAllByIdForUpdate(List.of(cycleId)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Slot has no cycle"));

        ExitRequest exit = exitRequestRepository.findById(exitId)
                .orElseThrow(ExitRequestNotFoundException::new);
        RoundParticipant participant = roundParticipantRepository.findById(participantId)
                .orElseThrow(() -> new IllegalStateException("Exiting participant not found"));
        Round round = getRoundOrThrow(roundId);

        if (round.getStatus() != RoundStatus.ACTIVE)
            throw new RoundNotActiveException();

        if (exit.getStatus() != ExitStatus.PENDING_SETTLEMENT)
            throw new ExitNotPendingSettlementException();

        if (buyInRepository.existsByExitRequestId(exitId))
            throw new ExitAlreadySettledException();

        // Nothing to take over from someone who has already collected.
        if (cycle.getStatus() == CycleStatus.PAID)
            throw new NothingToBuyIntoException();

        long exposure = exposureOf(participantId);
        if (exposure > 0)
            throw new LeaverOwesGroupException();      // a debt is not transferable
        if (exposure == 0)
            throw new NothingToBuyIntoException();     // nothing owed back, so nothing to buy

        long amount = -exposure;                        // what the leaver has put in
        if (request.amountKobo() != amount)
            throw new BuyInAmountMismatchException();

        LedgerAccount pool = ledgerAccountRepository
                .findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, roundId)
                .orElseThrow(() -> new IllegalStateException("Active round has no pool account"));

        Instant now = Instant.now(clock);
        UUID buyInId = UUID.randomUUID();

        // Two postings, not one netted movement: the ledger shows both halves.
        UUID buyInTransactionId = ledgerService.post(
                EntryType.BUY_IN,
                buyInId,
                List.of(
                        new PostingLine(LedgerAccounts.PLATFORM_CASH_ID, amount),
                        new PostingLine(pool.getId(), -amount)));

        UUID refundTransactionId = ledgerService.post(
                EntryType.REFUND,
                buyInId,
                List.of(
                        new PostingLine(LedgerAccounts.PLATFORM_CASH_ID, -amount),
                        new PostingLine(pool.getId(), amount)));

        BuyIn buyIn = BuyIn.builder()
                .id(buyInId)
                .roundId(roundId)
                .exitRequestId(exitId)
                .participantId(participantId)
                .leaverUserId(leaverUserId)
                .replacementUserId(replacementUserId)
                .amountKobo(amount)
                .method(request.method() == null ? BuyInMethod.ONLINE : request.method())
                .recordedBy(callerId)
                .idempotencyKey(idempotencyKey)
                .buyInTransactionId(buyInTransactionId)
                .refundTransactionId(refundTransactionId)
                .createdAt(now)
                .build();

        try {
            buyInRepository.saveAndFlush(buyIn);
        } catch (DataIntegrityViolationException e) {
            // Another buy-in for this exit committed between our check above and this insert.
            throw new ExitAlreadySettledException();
        }

        // The slot passes to the replacement. Position, cycle and contribution history
        // all hang off participant_id, so they come with it.
        participant.transferTo(replacementUserId);

        // It is the replacement's slot now, not the leaver's — so it goes back to ACTIVE
        // rather than EXITED, and the cycle is NOT vacated.
        participant.markActive();
        roundParticipantRepository.save(participant);

        exit.complete(now);
        exitRequestRepository.save(exit);

        // Swaps the leaver agreed to can no longer stand.
        var staleSwaps = swapRequestRepository.findPendingInvolvingForUpdate(List.of(participantId));
        staleSwaps.forEach(s -> s.supersede(now));
        swapRequestRepository.saveAll(staleSwaps);

        return buyInSummaries(List.of(buyIn)).getFirst();
    }

    @Transactional
    public SettlementSummary settleVacantCycle(UUID callerId, UUID cycleId) {

        // Phase 1 — discovery, no locks.
        UUID roundId;
        {
            Cycle discovered = cycleRepository.findById(cycleId)
                    .orElseThrow(CycleNotFoundException::new);
            Round round = getRoundOrThrow(discovered.getRoundId());

            GroupMember caller = groupMemberRepository
                    .findByGroupIdAndUserId(round.getGroupId(), callerId)
                    .orElseThrow(NotGroupMemberException::new);
            if (caller.getRole() != GroupRole.ADMIN)
                throw new InsufficientRoleException();

            roundId = round.getId();
        }

        entityManager.clear();

        // Phase 2 — lock the cycle, then reload everything fresh.
        Cycle cycle = cycleRepository.findAllByIdForUpdate(List.of(cycleId)).stream()
                .findFirst()
                .orElseThrow(CycleNotFoundException::new);
        Round round = getRoundOrThrow(roundId);

        if (cycle.getStatus() == CycleStatus.SETTLED)
            throw new CycleAlreadySettledException();

        if (cycle.getStatus() != CycleStatus.VACANT) {
            // Not vacated yet: only valid if its beneficiary is on their way out.
            if (cycle.getStatus() == CycleStatus.PAID || cycle.getBeneficiaryId() == null)
                throw new CycleNotVacantException();

            RoundParticipant beneficiary = roundParticipantRepository.findById(cycle.getBeneficiaryId())
                    .orElseThrow(CycleNotVacantException::new);

            ExitRequest openExit = exitRequestRepository
                    .findByParticipantIdAndStatus(beneficiary.getId(), ExitStatus.PENDING_SETTLEMENT)
                    .orElseThrow(CycleNotVacantException::new);

            cycle.markVacant(openExit.getId());
            cycleRepository.save(cycle);
        }

        if (LocalDate.now(clock).isBefore(cycle.getPayoutOn()))
            throw new SettlementNotYetDueException();

        long remaining = availableInPot(cycle);
        if (remaining <= 0)
            throw new NothingToSettleException();

        LedgerAccount pool = ledgerAccountRepository
                .findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, roundId)
                .orElseThrow(() -> new IllegalStateException("Round has no pool account"));

        Instant now = Instant.now(clock);
        long potGross = potOf(cycle);

        // ---- The leaver's refund comes first: their money is why this pot exists.
        RefundSummary refundSummary = null;
        long refundOwed = refundOwedOf(cycle);

        if (refundOwed > 0) {
            long refundAmount = Math.min(refundOwed, remaining);

            ExitRequest exit = exitRequestRepository.findById(cycle.getVacatedByExitId())
                    .orElseThrow(() -> new IllegalStateException("Vacated cycle points at a missing exit"));
            RoundParticipant leaverSlot = roundParticipantRepository.findById(exit.getParticipantId())
                    .orElseThrow(() -> new IllegalStateException("Exiting participant not found"));

            UUID refundId = UUID.randomUUID();
            UUID refundTransactionId = ledgerService.post(
                    EntryType.REFUND,
                    refundId,
                    List.of(
                            new PostingLine(LedgerAccounts.PLATFORM_CASH_ID, -refundAmount),
                            new PostingLine(pool.getId(), refundAmount)));

            Refund refund = refundRepository.save(Refund.builder()
                    .id(refundId)
                    .cycleId(cycleId)
                    .exitRequestId(exit.getId())
                    .participantId(exit.getParticipantId())
                    .userId(leaverSlot.getUserId())          // snapshot: the slot may move on later
                    .expectedAmountKobo(refundOwed)
                    .actualAmountKobo(refundAmount)
                    .ledgerTransactionId(refundTransactionId)
                    .createdAt(now)
                    .build());

            remaining -= refundAmount;

            // Fully refunded means they are finally out. The cycle is already vacant,
            // so completeExit is not the right path here.
            if (refundAmount == refundOwed) {
                exit.complete(now);
                exitRequestRepository.save(exit);
                leaverSlot.markExited();
                roundParticipantRepository.save(leaverSlot);
            }

            User recipient = userRepository.findById(leaverSlot.getUserId())
                    .orElseThrow(() -> new IllegalStateException("User not found"));
            refundSummary = exitMapper.toRefundSummary(refund, userMapper.toSummary(recipient));
        }

        // ---- Then the shortfall claims, oldest first.
        Distribution distribution = shortfallDistributor.distribute(
                shortfallClaimRepository.findOpenByRoundIdOldestFirst(roundId), remaining, cycleId, pool.getId(), now);
        remaining = distribution.remaining();
        List<ShortfallClaim> settledClaims = distribution.settled();

        // ---- SETTLED only when nothing is left open against this round.
        // Otherwise the cycle stays VACANT so a later pass can distribute more,
        // and so it keeps accepting the contributions that fund it.
        boolean nothingOutstanding = refundOwedOf(cycle) == 0
                && shortfallClaimRepository.findOpenByRoundIdOldestFirst(roundId).isEmpty();

        if (nothingOutstanding) {
            cycle.markSettled();
            cycleRepository.save(cycle);
        }

        List<ShortfallClaimSummary> claimSummaries = shortfallClaimSummaries(settledClaims);

        return new SettlementSummary(
                cycleId, potGross, refundSummary, claimSummaries, remaining,
                cycleRepository.findById(cycleId).orElseThrow().getStatus());
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

    public List<BuyInSummary> listBuyInsForRound(UUID callerId, UUID roundId) {
        Round round = getRoundOrThrow(roundId);
        requireGroupMembership(round.getGroupId(), callerId);

        return buyInSummaries(buyInRepository.findByRoundId(roundId));
    }

    public BuyInSummary getBuyInForExit(UUID callerId, UUID exitId) {
        ExitRequest exit = exitRequestRepository.findById(exitId)
                .orElseThrow(ExitRequestNotFoundException::new);
        Round round = getRoundOrThrow(exit.getRoundId());
        requireGroupMembership(round.getGroupId(), callerId);

        BuyIn buyIn = buyInRepository.findByExitRequestId(exitId)
                .orElseThrow(BuyInNotFoundException::new);

        return buyInSummaries(List.of(buyIn)).getFirst();
    }

    public List<RefundSummary> listRefundsForRound(UUID callerId, UUID roundId) {
        Round round = getRoundOrThrow(roundId);
        requireGroupMembership(round.getGroupId(), callerId);

        List<UUID> cycleIds = cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId).stream()
                .map(Cycle::getId)
                .toList();

        if (cycleIds.isEmpty())
            return List.of();

        return refundSummaries(refundRepository.findByCycleIdIn(cycleIds));
    }

    public RefundSummary getRefundForExit(UUID callerId, UUID exitId) {
        ExitRequest exit = exitRequestRepository.findById(exitId)
                .orElseThrow(ExitRequestNotFoundException::new);
        Round round = getRoundOrThrow(exit.getRoundId());
        requireGroupMembership(round.getGroupId(), callerId);

        Refund refund = refundRepository.findByExitRequestId(exitId)
                .orElseThrow(RefundNotFoundException::new);

        return refundSummaries(List.of(refund)).getFirst();
    }

    /** Not-fully-settled claims, oldest first — the order a vacant pot pays them in. */
    public List<ShortfallClaimSummary> listOpenShortfallClaims(UUID callerId, UUID roundId) {
        Round round = getRoundOrThrow(roundId);
        requireGroupMembership(round.getGroupId(), callerId);

        return shortfallClaimSummaries(shortfallClaimRepository.findOpenByRoundIdOldestFirst(roundId));
    }

    public VacantCycleSummary getVacantCycleStatus(UUID callerId, UUID cycleId) {
        Cycle cycle = cycleRepository.findById(cycleId).orElseThrow(CycleNotFoundException::new);
        Round round = getRoundOrThrow(cycle.getRoundId());
        requireGroupMembership(round.getGroupId(), callerId);

        boolean beneficiaryIsLeaving = cycle.getBeneficiaryId() != null
                && exitRequestRepository
                .findByParticipantIdAndStatus(cycle.getBeneficiaryId(), ExitStatus.PENDING_SETTLEMENT)
                .isPresent();

        if (cycle.getStatus() != CycleStatus.VACANT
                && cycle.getStatus() != CycleStatus.SETTLED
                && !beneficiaryIsLeaving)
            throw new CycleNotVacantException();

        long pot = potOf(cycle);
        long refundOwed = refundOwedOf(cycle);
        long openClaims = shortfallClaimRepository.findOpenByRoundIdOldestFirst(round.getId()).stream()
                .mapToLong(c -> c.getAmountKobo() - c.getSettledAmountKobo())
                .sum();

        // Mirrors settleVacantCycle's preconditions.
        boolean readyToSettle = (cycle.getStatus() == CycleStatus.VACANT || beneficiaryIsLeaving)
                && cycle.getStatus() != CycleStatus.PAID
                && !LocalDate.now(clock).isBefore(cycle.getPayoutOn())
                && pot > 0;

        return new VacantCycleSummary(cycleId, pot, refundOwed, openClaims, readyToSettle);
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
            lockedCycle.markVacant(exit.getId());
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
        long refunded = refundRepository.sumActualAmountKoboByParticipantId(participantId);
        long claimsSettled = shortfallSettlementRepository.sumAmountKoboByClaimParticipantId(participantId);

        return collected + refunded + claimsSettled - contributed - repaid;
    }

    long potOf(Cycle cycle) {
        // What came in for this cycle specifically — not the pool balance,
        // which mixes every cycle in the round together.
        return contributionRepository.sumAmountKoboByCycleId(cycle.getId());
    }

    /** The pot minus everything already paid out of it. Settlement can run repeatedly. */
    private long availableInPot(Cycle cycle) {
        long refunded = refundRepository.findByCycleId(cycle.getId()).stream()
                .mapToLong(Refund::getActualAmountKobo)
                .sum();
        long distributed = shortfallSettlementRepository.sumAmountKoboByFundedByCycleId(cycle.getId());

        return potOf(cycle) - refunded - distributed;
    }

    long refundOwedOf(Cycle cycle) {
        if (cycle.getVacatedByExitId() == null)
            return 0;
        if (refundRepository.existsByExitRequestId(cycle.getVacatedByExitId()))
            return 0;

        ExitRequest exit = exitRequestRepository.findById(cycle.getVacatedByExitId())
                .orElseThrow(() -> new IllegalStateException("Vacated cycle points at a missing exit"));

        long exposure = exposureOf(exit.getParticipantId());
        return exposure < 0 ? -exposure : 0;     // negative exposure is money owed to them
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

    /**
     * Leaver and replacement come from the ids snapshotted on the row, never from the
     * participant — after the transfer the participant belongs to the replacement.
     * One user query regardless of row count.
     */
    private List<BuyInSummary> buyInSummaries(List<BuyIn> buyIns) {
        if (buyIns.isEmpty())
            return List.of();

        Set<UUID> userIds = new HashSet<>();
        buyIns.forEach(b -> {
            userIds.add(b.getLeaverUserId());
            userIds.add(b.getReplacementUserId());
        });

        Map<UUID, UserSummary> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, userMapper::toSummary));

        return buyIns.stream()
                .map(b -> exitMapper.toBuyInSummary(
                        b, usersById.get(b.getLeaverUserId()), usersById.get(b.getReplacementUserId())))
                .toList();
    }

    /** Recipient comes from the snapshotted userId, never the participant. One user query. */
    private List<RefundSummary> refundSummaries(List<Refund> refunds) {
        if (refunds.isEmpty())
            return List.of();

        Map<UUID, UserSummary> usersById = userRepository.findAllById(
                        refunds.stream().map(Refund::getUserId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(User::getId, userMapper::toSummary));

        return refunds.stream()
                .map(r -> exitMapper.toRefundSummary(r, usersById.get(r.getUserId())))
                .toList();
    }

    private List<ShortfallClaimSummary> shortfallClaimSummaries(List<ShortfallClaim> claims) {
        if (claims.isEmpty())
            return List.of();

        Map<UUID, UserSummary> usersByParticipantId =
                userSummariesByParticipantId(claims.stream().map(ShortfallClaim::getParticipantId).toList());

        return claims.stream()
                .map(c -> payoutMapper.toShortfallClaimSummary(c, usersByParticipantId.get(c.getParticipantId())))
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
