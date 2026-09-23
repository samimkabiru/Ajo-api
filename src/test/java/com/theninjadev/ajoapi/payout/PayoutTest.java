package com.theninjadev.ajoapi.payout;

import com.theninjadev.ajoapi.contribution.Contribution;
import com.theninjadev.ajoapi.contribution.ContributionRepository;
import com.theninjadev.ajoapi.ledger.AccountType;
import com.theninjadev.ajoapi.ledger.LedgerAccountRepository;
import com.theninjadev.ajoapi.ledger.LedgerAccounts;
import com.theninjadev.ajoapi.ledger.LedgerEntry;
import com.theninjadev.ajoapi.ledger.LedgerEntryRepository;
import com.theninjadev.ajoapi.round.Cycle;
import com.theninjadev.ajoapi.round.CycleRepository;
import com.theninjadev.ajoapi.round.CycleStatus;
import com.theninjadev.ajoapi.round.ParticipantSummary;
import com.theninjadev.ajoapi.round.RoundDetail;
import com.theninjadev.ajoapi.round.RoundRepository;
import com.theninjadev.ajoapi.round.RoundStatus;
import com.theninjadev.ajoapi.swap.PositionSwapRequestRepository;
import com.theninjadev.ajoapi.swap.SwapStatus;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.ApiTestClient;
import com.theninjadev.ajoapi.testsupport.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class PayoutTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;          // ₦10,000 per member per cycle
    private static final long FULL_POT = 3 * AMOUNT;         // three participants
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CycleRepository cycleRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private PayoutRepository payoutRepository;
    @Autowired private ShortfallClaimRepository shortfallClaimRepository;
    @Autowired private ContributionRepository contributionRepository;
    @Autowired private LedgerAccountRepository ledgerAccountRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private PositionSwapRequestRepository swapRequestRepository;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper);
    }

    private record Fixture(UUID roundId,
                           TestUser admin,
                           TestUser ada,
                           TestUser eze,
                           List<TestUser> members,
                           List<Cycle> cycles,
                           Map<UUID, UUID> participantIdByUserId,
                           Map<UUID, TestUser> userByParticipantId) {

        TestUser beneficiaryOf(Cycle cycle) {
            return userByParticipantId.get(cycle.getBeneficiaryId());
        }

        Cycle firstCycleNotBelongingTo(TestUser user) {
            return cycles.stream()
                    .filter(c -> !beneficiaryOf(c).id().equals(user.id()))
                    .findFirst()
                    .orElseThrow();
        }

        List<TestUser> membersOtherThan(TestUser user) {
            return members.stream()
                    .filter(m -> !m.id().equals(user.id()))
                    .toList();
        }
    }

    // Structural

    @Test
    void fullPoolPaysTheFullExpectedAmount() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();
        var beneficiary = f.beneficiaryOf(cycle);

        contributeAll(f, cycle);
        var payout = client.payout(beneficiary, cycle.getId(), beneficiary.id(), newKey());

        assertThat(payout.expectedAmountKobo()).isEqualTo(FULL_POT);
        assertThat(payout.actualAmountKobo()).isEqualTo(FULL_POT);
        assertThat(payout.shortfallKobo()).isZero();
        assertThat(payout.beneficiary().id()).isEqualTo(beneficiary.id());
    }

    @Test
    void payoutPostsTwoEntriesSummingToZero() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();

        contributeAll(f, cycle);
        var payout = client.payout(f.admin(), cycle.getId(), f.beneficiaryOf(cycle).id(), newKey());

        var entries = ledgerEntryRepository.findByTransactionId(payout.ledgerTransactionId());

        assertThat(entries).hasSize(2);
        assertThat(entries.stream().mapToLong(LedgerEntry::getAmountKobo).sum()).isZero();

        var cashEntry = entries.stream()
                .filter(e -> e.getAccountId().equals(LedgerAccounts.PLATFORM_CASH_ID))
                .findFirst()
                .orElseThrow();
        assertThat(cashEntry.getAmountKobo()).isEqualTo(-FULL_POT);   // cash leaves the platform
    }

    @Test
    void poolBalanceReturnsToZeroAfterFullPayout() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();

        contributeAll(f, cycle);
        assertThat(poolBalance(f.roundId())).isEqualTo(-FULL_POT);    // liability before payout

        client.payout(f.admin(), cycle.getId(), f.beneficiaryOf(cycle).id(), newKey());

        assertThat(poolBalance(f.roundId())).isZero();
    }

    @Test
    void payoutMarksTheCyclePaid() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();

        contributeAll(f, cycle);
        client.payout(f.admin(), cycle.getId(), f.beneficiaryOf(cycle).id(), newKey());

        var reloaded = cycleRepository.findById(cycle.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CycleStatus.PAID);
    }

    // The balance cap

    @Test
    void partialPoolPaysOnlyWhatWasCollected() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();

        // Two of three contribute.
        client.contribute(f.admin(), cycle.getId(), AMOUNT, f.admin().id(), newKey());
        client.contribute(f.ada(), cycle.getId(), AMOUNT, f.ada().id(), newKey());

        var payout = client.payout(f.admin(), cycle.getId(), f.beneficiaryOf(cycle).id(), newKey());

        assertThat(payout.expectedAmountKobo()).isEqualTo(FULL_POT);
        assertThat(payout.actualAmountKobo()).isEqualTo(2 * AMOUNT);
        assertThat(payout.shortfallKobo()).isEqualTo(AMOUNT);
        assertThat(poolBalance(f.roundId())).isZero();                // nothing left over, nothing overdrawn
    }

    @Test
    void partialPayoutCreatesAnOpenShortfallClaimForTheBeneficiary() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();

        client.contribute(f.admin(), cycle.getId(), AMOUNT, f.admin().id(), newKey());
        client.contribute(f.ada(), cycle.getId(), AMOUNT, f.ada().id(), newKey());
        client.payout(f.admin(), cycle.getId(), f.beneficiaryOf(cycle).id(), newKey());

        var claim = shortfallClaimRepository.findByCycleId(cycle.getId()).orElseThrow();

        assertThat(claim.getAmountKobo()).isEqualTo(AMOUNT);
        assertThat(claim.getParticipantId()).isEqualTo(cycle.getBeneficiaryId());
        assertThat(claim.getSettledAt()).isNull();                    // nothing settles claims yet
    }

    @Test
    void fullPayoutCreatesNoShortfallClaim() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();

        contributeAll(f, cycle);
        client.payout(f.admin(), cycle.getId(), f.beneficiaryOf(cycle).id(), newKey());

        assertThat(shortfallClaimRepository.findByCycleId(cycle.getId())).isEmpty();
    }

    @Test
    void emptyPoolIsRejectedAndWritesNothing() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();

        client.payoutAndExpect(f.admin(), cycle.getId(), f.beneficiaryOf(cycle).id(), newKey(), 409);

        assertThat(payoutRepository.findByCycleId(cycle.getId())).isEmpty();
        var reloaded = cycleRepository.findById(cycle.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isNotEqualTo(CycleStatus.PAID);
    }

    // Lifecycle

    @Test
    void finalPayoutCompletesTheRound() throws Exception {
        var f = setUp(PAST_START);

        runFullRound(f);

        var round = roundRepository.findById(f.roundId()).orElseThrow();
        assertThat(round.getStatus()).isEqualTo(RoundStatus.COMPLETED);
    }

    @Test
    void roundStaysActiveUntilTheLastCycleIsPaid() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();

        contributeAll(f, cycle);
        client.payout(f.admin(), cycle.getId(), f.beneficiaryOf(cycle).id(), newKey());

        var round = roundRepository.findById(f.roundId()).orElseThrow();
        assertThat(round.getStatus()).isEqualTo(RoundStatus.ACTIVE);
    }

    @Test
    void contributingToACompletedRoundIsRejected() throws Exception {
        // The RoundNotActiveException case that was unreachable in slice 5.
        var f = setUp(PAST_START);
        runFullRound(f);

        client.contributeAndExpect(f.ada(), f.cycles().getFirst().getId(), AMOUNT, f.ada().id(), newKey(), 409);
    }

    // Idempotency

    @Test
    void sameKeyTwiceCreatesOnePayoutAndOnePosting() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();
        var expected = f.beneficiaryOf(cycle).id();
        var key = newKey();

        contributeAll(f, cycle);
        var first = client.payout(f.admin(), cycle.getId(), expected, key);
        var second = client.payout(f.admin(), cycle.getId(), expected, key);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.ledgerTransactionId()).isEqualTo(first.ledgerTransactionId());
        assertThat(ledgerEntryRepository.findByTransactionId(first.ledgerTransactionId())).hasSize(2);
        assertThat(poolBalance(f.roundId())).isZero();                // not paid out twice
    }

    @Test
    void retryingTheFinalPayoutAfterRoundCompletesReturnsTheOriginal() throws Exception {
        // Regression guard: the final payout flips the round to COMPLETED, so a retry
        // must hit the idempotency check before the round-status check.
        var f = setUp(PAST_START);
        var payouts = runFullRound(f);

        var lastCycle = f.cycles().getLast();
        var lastPayout = payouts.getLast();

        var retried = client.payout(f.admin(), lastCycle.getId(),
                f.beneficiaryOf(lastCycle).id(), lastPayout.idempotencyKey());

        assertThat(retried.id()).isEqualTo(lastPayout.summary().id());
    }

    @Test
    void reusingAKeyForADifferentCycleIsRejected() throws Exception {
        var f = setUp(PAST_START);
        var first = f.cycles().get(0);
        var second = f.cycles().get(1);
        var key = newKey();

        contributeAll(f, first);
        client.payout(f.admin(), first.getId(), f.beneficiaryOf(first).id(), key);

        client.payoutAndExpect(f.admin(), second.getId(), f.beneficiaryOf(second).id(), key, 409);
    }

    @Test
    void payingOutTheSameCycleTwiceIsRejected() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();
        var expected = f.beneficiaryOf(cycle).id();

        contributeAll(f, cycle);
        client.payout(f.admin(), cycle.getId(), expected, newKey());

        client.payoutAndExpect(f.admin(), cycle.getId(), expected, newKey(), 409);
    }

    // Authorization and timing

    @Test
    void nonBeneficiaryNonAdminCannotTriggerAPayout() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.firstCycleNotBelongingTo(f.ada());

        contributeAll(f, cycle);

        client.payoutAndExpect(f.ada(), cycle.getId(), f.beneficiaryOf(cycle).id(), newKey(), 403);
    }

    @Test
    void adminRecordingAPayoutAttributesItToTheBeneficiary() throws Exception {
        // Regression guard for the caller-versus-subject bug: the payout belongs
        // to the cycle's beneficiary, not to the admin who recorded it.
        var f = setUp(PAST_START);
        var cycle = f.firstCycleNotBelongingTo(f.admin());
        var beneficiary = f.beneficiaryOf(cycle);

        contributeAll(f, cycle);
        var payout = client.payout(f.admin(), cycle.getId(), beneficiary.id(), newKey());

        assertThat(payout.recordedBy()).isEqualTo(f.admin().id());
        assertThat(payout.beneficiary().id()).isEqualTo(beneficiary.id());
        assertThat(payout.participantId()).isEqualTo(cycle.getBeneficiaryId());
    }

    @Test
    void payoutBeforeTheDueDateIsRejected() throws Exception {
        var f = setUp(LocalDate.now().plusMonths(6).withDayOfMonth(28));
        var cycle = f.cycles().getFirst();

        client.payoutAndExpect(f.admin(), cycle.getId(), f.beneficiaryOf(cycle).id(), newKey(), 409);
    }

    // Confirming who is being paid

    @Test
    void payoutWithAStaleExpectedBeneficiaryIsRejectedAndWritesNothing() throws Exception {
        // The admin believes they are paying someone who is not this cycle's beneficiary —
        // exactly what they would see if a swap had moved the cycle since they loaded it.
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();
        var notTheBeneficiary = f.membersOtherThan(f.beneficiaryOf(cycle)).getFirst();

        contributeAll(f, cycle);

        client.payoutAndExpect(f.admin(), cycle.getId(), notTheBeneficiary.id(), newKey(), 409);

        assertThat(payoutRepository.findByCycleId(cycle.getId())).isEmpty();
        assertThat(poolBalance(f.roundId())).isEqualTo(-FULL_POT);    // pool untouched
    }

    @Test
    void payoutWithoutAnExpectedBeneficiaryIsRejected() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();

        contributeAll(f, cycle);

        client.payoutAndExpect(f.admin(), cycle.getId(), null, newKey(), 400);
    }

    // Swap supersession

    @Test
    void payoutSupersedesPendingSwapsInvolvingTheBeneficiaryOnly() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();
        var paid = f.beneficiaryOf(cycle);

        var others = f.membersOtherThan(paid);
        var b = others.get(0);
        var c = others.get(1);

        var outgoing  = client.requestSwap(paid, f.roundId(), c.id());   // made by the beneficiary
        var incoming  = client.requestSwap(b, f.roundId(), paid.id());   // sent to the beneficiary
        var unrelated = client.requestSwap(c, f.roundId(), b.id());      // does not involve them at all

        contributeAll(f, cycle);
        client.payout(f.admin(), cycle.getId(), paid.id(), newKey());

        assertThat(swapStatus(outgoing.id())).isEqualTo(SwapStatus.SUPERSEDED);
        assertThat(swapStatus(incoming.id())).isEqualTo(SwapStatus.SUPERSEDED);
        assertThat(swapStatus(unrelated.id())).isEqualTo(SwapStatus.PENDING);
    }

    // The invariant

    @Test
    void aCompleteRoundNetsToZeroForEveryParticipant() throws Exception {
        var f = setUp(PAST_START);
        long cashBefore = ledgerEntryRepository.sumAmountKoboByAccountId(LedgerAccounts.PLATFORM_CASH_ID);

        runFullRound(f);

        for (UUID participantId : f.participantIdByUserId().values()) {
            long contributed = contributionRepository.findByParticipantId(participantId).stream()
                    .mapToLong(Contribution::getAmountKobo)
                    .sum();
            long collected = payoutRepository.findByParticipantId(participantId).stream()
                    .mapToLong(Payout::getActualAmountKobo)
                    .sum();

            assertThat(contributed)
                    .as("participant %s contributed", participantId)
                    .isEqualTo(FULL_POT);                              // paid every one of 3 months
            assertThat(collected)
                    .as("participant %s collected", participantId)
                    .isEqualTo(contributed);                           // the invariant
        }

        // And the system as a whole holds nothing back.
        assertThat(poolBalance(f.roundId())).isZero();
        assertThat(ledgerEntryRepository.sumAmountKoboByAccountId(LedgerAccounts.PLATFORM_CASH_ID))
                .isEqualTo(cashBefore);
    }

    // Round-level helpers

    private record RecordedPayout(PayoutSummary summary, String idempotencyKey) {}

    /** Every member contributes to every cycle, and every cycle pays out, in order. */
    private List<RecordedPayout> runFullRound(Fixture f) throws Exception {
        List<RecordedPayout> payouts = new ArrayList<>();
        for (Cycle cycle : f.cycles()) {
            contributeAll(f, cycle);
            String key = newKey();
            var summary = client.payout(f.admin(), cycle.getId(), f.beneficiaryOf(cycle).id(), key);
            payouts.add(new RecordedPayout(summary, key));
        }
        return payouts;
    }

    private void contributeAll(Fixture f, Cycle cycle) throws Exception {
        for (TestUser member : f.members()) {
            client.contribute(member, cycle.getId(), AMOUNT, member.id(), newKey());
        }
    }

    private long poolBalance(UUID roundId) {
        var pool = ledgerAccountRepository
                .findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, roundId)
                .orElseThrow();
        return ledgerEntryRepository.sumAmountKoboByAccountId(pool.getId());
    }

    private SwapStatus swapStatus(UUID swapId) {
        return swapRequestRepository.findById(swapId).orElseThrow().getStatus();
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    // Setup — three members, one round, activated

    private Fixture setUp(LocalDate firstPayoutDate) throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var eze = client.registerUser("Eze");
        var members = List.of(admin, ada, eze);

        var groupId = client.createGroup(admin, "Alice's Ajo");
        client.addToGroup(admin, groupId, ada);
        client.addToGroup(admin, groupId, eze);

        var roundId = client.createRound(admin, groupId, AMOUNT, firstPayoutDate);
        for (TestUser member : members) {
            client.addParticipant(admin, roundId, member);
        }

        RoundDetail detail = client.activate(admin, roundId);

        Map<UUID, TestUser> usersById = members.stream()
                .collect(Collectors.toMap(TestUser::id, Function.identity()));

        Map<UUID, UUID> participantIdByUserId = detail.participants().stream()
                .collect(Collectors.toMap(p -> p.user().id(), ParticipantSummary::id));

        Map<UUID, TestUser> userByParticipantId = detail.participants().stream()
                .collect(Collectors.toMap(ParticipantSummary::id, p -> usersById.get(p.user().id())));

        var cycles = cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId);

        return new Fixture(roundId, admin, ada, eze, members, cycles,
                participantIdByUserId, userByParticipantId);
    }
}
