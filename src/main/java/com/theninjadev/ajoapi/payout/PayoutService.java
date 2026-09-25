package com.theninjadev.ajoapi.payout;

import com.theninjadev.ajoapi.auth.User;
import com.theninjadev.ajoapi.auth.UserMapper;
import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.auth.UserSummary;
import com.theninjadev.ajoapi.contribution.ContributionRepository;
import com.theninjadev.ajoapi.contribution.CycleNotFoundException;
import com.theninjadev.ajoapi.contribution.MissingIdempotencyKeyException;
import com.theninjadev.ajoapi.contribution.RoundNotActiveException;
import com.theninjadev.ajoapi.group.GroupMember;
import com.theninjadev.ajoapi.group.GroupMemberRepository;
import com.theninjadev.ajoapi.group.GroupRole;
import com.theninjadev.ajoapi.group.NotGroupMemberException;
import com.theninjadev.ajoapi.ledger.*;
import com.theninjadev.ajoapi.round.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.theninjadev.ajoapi.swap.PositionSwapRequestRepository;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class PayoutService {

    private final PayoutRepository payoutRepository;
    private final ShortfallClaimRepository shortfallClaimRepository;
    private final CycleRepository cycleRepository;
    private final RoundRepository roundRepository;
    private final RoundParticipantRepository roundParticipantRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PayoutMapper payoutMapper;
    private final LedgerService ledgerService;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final Clock clock;
    private final PositionSwapRequestRepository swapRequestRepository;
    private final ContributionRepository contributionRepository;
    private final ShortfallSettlementRepository shortfallSettlementRepository;
    private final ShortfallDistributor shortfallDistributor;

    public List<PayoutSummary> listForRound(UUID callerId, UUID roundId) {
        Round round = getRoundOrThrow(roundId);
        requireGroupMembership(round.getGroupId(), callerId);

        List<UUID> cycleIds = cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId).stream()
                .map(Cycle::getId)
                .toList();

        if (cycleIds.isEmpty())
            return List.of();

        return payoutSummaries(payoutRepository.findByCycleIdIn(cycleIds));
    }

    public List<PayoutSummary> listForParticipant(UUID callerId, UUID participantId) {
        RoundParticipant participant = roundParticipantRepository.findById(participantId)
                .orElseThrow(RoundParticipantNotFoundException::new);
        Round round = getRoundOrThrow(participant.getRoundId());
        requireGroupMembership(round.getGroupId(), callerId);

        return payoutSummaries(payoutRepository.findByParticipantId(participantId));
    }

    public PayoutSummary getForCycle(UUID callerId, UUID cycleId) {
        Cycle cycle = cycleRepository.findById(cycleId).orElseThrow(CycleNotFoundException::new);
        Round round = getRoundOrThrow(cycle.getRoundId());
        requireGroupMembership(round.getGroupId(), callerId);

        Payout payout = payoutRepository.findByCycleId(cycleId).orElseThrow(PayoutNotFoundException::new);
        return payoutSummaries(List.of(payout)).getFirst();
    }

    public List<ShortfallClaimSummary> listShortfallClaimsForRound(UUID callerId, UUID roundId) {
        Round round = getRoundOrThrow(roundId);
        requireGroupMembership(round.getGroupId(), callerId);

        List<UUID> cycleIds = cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId).stream()
                .map(Cycle::getId)
                .toList();

        if (cycleIds.isEmpty())
            return List.of();

        return shortfallClaimSummaries(shortfallClaimRepository.findByCycleIdIn(cycleIds));
    }

    public List<ShortfallClaimSummary> listMyShortfallClaims(UUID callerId, UUID roundId) {
        getRoundOrThrow(roundId);

        RoundParticipant participant = roundParticipantRepository.findByRoundIdAndUserId(roundId, callerId)
                .orElseThrow(RoundParticipantNotFoundException::new);

        return shortfallClaimSummaries(
                shortfallClaimRepository.findByParticipantIdAndSettledAtIsNull(participant.getId()));
    }

    @Transactional
    public PayoutSummary payout(UUID callerId, UUID cycleId,
                                PayoutRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new MissingIdempotencyKeyException();

        Cycle cycle = cycleRepository.findAllByIdForUpdate(List.of(cycleId)).stream()
                .findFirst()
                .orElseThrow(CycleNotFoundException::new);
        Round round = getRoundOrThrow(cycle.getRoundId());
        requireGroupMembership(round.getGroupId(), callerId);

        Payout payout = payoutRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (payout != null) {
            if (!payout.getCycleId().equals(cycleId))
                throw new IdempotencyKeyReusedException();

            RoundParticipant participant = roundParticipantRepository
                    .findById(payout.getParticipantId())
                    .orElseThrow(() -> new IllegalStateException("Participant not found"));
            User user = userRepository
                    .findById(participant.getUserId())
                    .orElseThrow(() -> new IllegalStateException("User not found"));

            long claimed = shortfallClaimRepository.findByCycleId(cycleId)
                    .map(ShortfallClaim::getAmountKobo)
                    .orElse(0L);

            return payoutMapper.toPayoutSummary(payout, userMapper.toSummary(user), claimed);

        }

        if (round.getStatus() != RoundStatus.ACTIVE)
            throw new RoundNotActiveException();

        if (cycle.getBeneficiaryId() == null)
            throw new CycleHasNoBeneficiaryException();

        if (cycle.getStatus() == CycleStatus.PAID)
            throw new CycleAlreadyPaidOutException();

        if (payoutRepository.existsByCycleId(cycleId))
            throw new CycleAlreadyPaidOutException();

        if (LocalDate.now(clock).isBefore(cycle.getPayoutOn()))
            throw new PayoutNotYetDueException();

        RoundParticipant beneficiary = roundParticipantRepository
                .findById(cycle.getBeneficiaryId())
                .orElseThrow(() -> new IllegalStateException("Cycle beneficiary not found"));

        if (beneficiary.getStatus() != ParticipantStatus.ACTIVE)
            throw new BeneficiaryNotActiveException();

        if (!beneficiary.getUserId().equals(request.expectedBeneficiaryUserId()))
            throw new BeneficiaryChangedException();

        if (!beneficiary.getUserId().equals(callerId)) {
            GroupMember caller = groupMemberRepository
                    .findByGroupIdAndUserId(round.getGroupId(), callerId)
                    .orElseThrow(NotGroupMemberException::new);
            if (caller.getRole() != GroupRole.ADMIN)
                throw new NotCycleBeneficiaryException();
        }

        LedgerAccount pool = ledgerAccountRepository
                .findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, round.getId())
                .orElseThrow(() -> new IllegalStateException("Active round has no pool account"));

        long poolBalance = -ledgerService.balanceOf(pool.getId());

        long participantCount = roundParticipantRepository.findByRoundId(round.getId()).size();
        long expected = participantCount * round.getContributionAmountKobo();
        long capped = Math.min(expected, poolBalance);

        if (capped <= 0) throw new EmptyPoolException();

        // The beneficiary's own arrears come out of their payout. Capped at what is
        // available, so the payout can reach zero but never go negative.
        long arrears = Math.min(arrearsOf(beneficiary.getId(), cycle), capped);
        long actual = capped - arrears;

        // A claim means the group underpaid this person. Two things are not that:
        //  - the withheld arrears: claiming them would have the group owe a defaulter
        //    the very money they failed to pay;
        //  - their own missing contribution to this cycle: it already shows up as a
        //    smaller pot, so claiming it would do the same.
        long ownMissing = contributionRepository.existsByCycleIdAndParticipantId(cycleId, beneficiary.getId())
                ? 0
                : round.getContributionAmountKobo();
        long claimAmount = Math.max(0, expected - capped - ownMissing);

        UUID payoutId = UUID.randomUUID();
        Instant now = Instant.now(clock);

        // LedgerService rejects zero-amount lines, so a fully withheld payout posts nothing.
        UUID transactionId = actual == 0 ? null : ledgerService.post(
                EntryType.PAYOUT,
                payoutId,
                List.of(
                        new PostingLine(LedgerAccounts.PLATFORM_CASH_ID, -actual),
                        new PostingLine(pool.getId(), actual)));

        payout = Payout.builder()
                .id(payoutId)
                .cycleId(cycleId)
                .participantId(beneficiary.getId())
                .expectedAmountKobo(expected)
                .actualAmountKobo(actual)
                .arrearsWithheldKobo(arrears)
                .method(request.method())
                .recordedBy(callerId)
                .idempotencyKey(idempotencyKey)
                .ledgerTransactionId(transactionId)
                .createdAt(now)
                .build();

        try {
            payoutRepository.saveAndFlush(payout);
        } catch (DataIntegrityViolationException e) {
            throw new CycleAlreadyPaidOutException();
        }

        if (claimAmount > 0) {
            shortfallClaimRepository.save(ShortfallClaim.builder()
                    .id(UUID.randomUUID())
                    .cycleId(cycleId)
                    .participantId(beneficiary.getId())
                    .amountKobo(claimAmount)
                    .createdAt(now)
                    .build());
        }

        if (arrears > 0)
            distributeArrears(beneficiary.getId(), cycle, arrears, pool.getId(), now);

        cycle.markPaid();
        cycleRepository.save(cycle);

        var stale = swapRequestRepository.findPendingInvolvingForUpdate(List.of(beneficiary.getId()));
        stale.forEach(rq -> rq.supersede(now));
        swapRequestRepository.saveAll(stale);

        boolean allSettled = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.getId())
                .stream()
                .allMatch(c -> c.getStatus() == CycleStatus.PAID
                        || c.getStatus() == CycleStatus.VACANT
                        || c.getStatus() == CycleStatus.SETTLED);

        if (allSettled) {
            round.complete(now);
            roundRepository.save(round);
        }
        User beneficiaryUser = userRepository.findById(beneficiary.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found"));

        return payoutMapper.toPayoutSummary(payout, userMapper.toSummary(beneficiaryUser), claimAmount);
    }

    /**
     * Withheld arrears go first to the claims the missed contributions caused, then to
     * the round's other open claims. Anything left stays in the pool. The beneficiary's
     * own claims are excluded throughout: paying them from their own arrears would hand
     * the withheld money straight back.
     */
    private void distributeArrears(UUID beneficiaryId, Cycle cycle, long arrears,
                                   UUID poolAccountId, Instant now) {
        long remaining = arrears;

        List<UUID> missed = missedEarlierCycleIds(beneficiaryId, cycle);
        if (!missed.isEmpty()) {
            remaining = shortfallDistributor.distribute(
                    othersClaims(shortfallClaimRepository.findOpenByCycleIdsOldestFirst(missed), beneficiaryId),
                    remaining, cycle.getId(), poolAccountId, now).remaining();
        }

        if (remaining > 0) {
            shortfallDistributor.distribute(
                    othersClaims(shortfallClaimRepository.findOpenByRoundIdOldestFirst(cycle.getRoundId()), beneficiaryId),
                    remaining, cycle.getId(), poolAccountId, now);
        }
    }

    private static List<ShortfallClaim> othersClaims(List<ShortfallClaim> claims, UUID beneficiaryId) {
        return claims.stream().filter(c -> !c.getParticipantId().equals(beneficiaryId)).toList();
    }

    /**
     * What this participant owes for earlier cycles they did not pay into. The cycle being
     * collected is excluded: a missing contribution to it has already shrunk the pot.
     * Counted per missed cycle rather than from a running total, so a duplicate or early
     * contribution cannot distort it. Not exposure — exposure cannot see a missed month.
     */
    long arrearsOf(UUID participantId, Cycle cycle) {
        Round round = getRoundOrThrow(cycle.getRoundId());
        return round.getContributionAmountKobo() * missedEarlierCycleIds(participantId, cycle).size();
    }

    /** Cycles before this one that the participant has no contribution for. */
    private List<UUID> missedEarlierCycleIds(UUID participantId, Cycle cycle) {
        Set<UUID> paid = new HashSet<>(contributionRepository.findContributedCycleIdsByParticipantId(participantId));
        return cycleRepository.findByRoundIdOrderByCycleNumberAsc(cycle.getRoundId()).stream()
                .filter(c -> c.getCycleNumber() < cycle.getCycleNumber())
                .map(Cycle::getId)
                .filter(id -> !paid.contains(id))
                .toList();
    }

    private List<PayoutSummary> payoutSummaries(List<Payout> payouts) {
        if (payouts.isEmpty())
            return List.of();

        Map<UUID, RoundParticipant> participantsById = roundParticipantRepository.findAllById(
                        payouts.stream().map(Payout::getParticipantId).toList())
                .stream()
                .collect(Collectors.toMap(RoundParticipant::getId, Function.identity()));

        Map<UUID, User> usersById = userRepository.findAllById(
                        participantsById.values().stream().map(RoundParticipant::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        Map<UUID, Long> claimedByCycleId = shortfallClaimRepository.findByCycleIdIn(
                        payouts.stream().map(Payout::getCycleId).toList())
                .stream()
                .collect(Collectors.toMap(ShortfallClaim::getCycleId, ShortfallClaim::getAmountKobo));

        return payouts.stream()
                .map(payout -> payoutMapper.toPayoutSummary(
                        payout,
                        beneficiarySummary(payout, participantsById, usersById),
                        claimedByCycleId.getOrDefault(payout.getCycleId(), 0L)))
                .toList();
    }

    private UserSummary beneficiarySummary(Payout payout,
            Map<UUID, RoundParticipant> participantsById, Map<UUID, User> usersById) {
        RoundParticipant participant = participantsById.get(payout.getParticipantId());
        if (participant == null)
            return null;
        return userMapper.toSummary(usersById.get(participant.getUserId()));
    }

    private List<ShortfallClaimSummary> shortfallClaimSummaries(List<ShortfallClaim> claims) {
        if (claims.isEmpty())
            return List.of();

        Map<UUID, RoundParticipant> participantsById = roundParticipantRepository.findAllById(
                        claims.stream().map(ShortfallClaim::getParticipantId).toList())
                .stream()
                .collect(Collectors.toMap(RoundParticipant::getId, Function.identity()));

        Map<UUID, User> usersById = userRepository.findAllById(
                        participantsById.values().stream().map(RoundParticipant::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return claims.stream()
                .map(claim -> payoutMapper.toShortfallClaimSummary(
                        claim, claimantSummary(claim, participantsById, usersById)))
                .toList();
    }

    private UserSummary claimantSummary(ShortfallClaim claim,
            Map<UUID, RoundParticipant> participantsById, Map<UUID, User> usersById) {
        RoundParticipant participant = participantsById.get(claim.getParticipantId());
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
