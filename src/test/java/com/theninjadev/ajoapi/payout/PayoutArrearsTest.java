package com.theninjadev.ajoapi.payout;

import com.theninjadev.ajoapi.contribution.ContributionRepository;
import com.theninjadev.ajoapi.ledger.AccountType;
import com.theninjadev.ajoapi.ledger.LedgerAccountRepository;
import com.theninjadev.ajoapi.ledger.LedgerAccounts;
import com.theninjadev.ajoapi.ledger.LedgerEntryRepository;
import com.theninjadev.ajoapi.round.Cycle;
import com.theninjadev.ajoapi.round.CycleRepository;
import com.theninjadev.ajoapi.round.CycleStatus;
import com.theninjadev.ajoapi.round.ParticipantSummary;
import com.theninjadev.ajoapi.round.RoundRepository;
import com.theninjadev.ajoapi.round.RoundStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arrears netting: a member who missed earlier contributions has them withheld from their
 * payout, and the withheld money settles the claims their missed contributions caused.
 *
 * Three members, P1, P2 and D, collect in cycles 1, 2 and 3 respectively. Positions are
 * shuffled at activation, so the roles are read from the cycles, not from the names.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PayoutArrearsTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;               // ₦10,000 per member per cycle
    private static final long FULL_POT = 3 * AMOUNT;
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);   // every cycle already due

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CycleRepository cycleRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private PayoutRepository payoutRepository;
    @Autowired private ShortfallClaimRepository shortfallClaimRepository;
    @Autowired private ShortfallSettlementRepository shortfallSettlementRepository;
    @Autowired private ContributionRepository contributionRepository;
    @Autowired private LedgerAccountRepository ledgerAccountRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper);
    }

    private record Fixture(UUID roundId,
                           TestUser admin,
                           List<TestUser> members,
                           List<Cycle> cycles,
                           Map<UUID, UUID> participantIdByUserId,
                           Map<UUID, TestUser> userByParticipantId) {

        Cycle c1() { return cycles.get(0); }
        Cycle c2() { return cycles.get(1); }
        Cycle c3() { return cycles.get(2); }

        TestUser p1() { return beneficiaryOf(c1()); }
        TestUser p2() { return beneficiaryOf(c2()); }
        TestUser d()  { return beneficiaryOf(c3()); }

        TestUser beneficiaryOf(Cycle cycle) {
            return userByParticipantId.get(cycle.getBeneficiaryId());
        }

        UUID participantIdOf(TestUser user) {
            return participantIdByUserId.get(user.id());
        }
    }

    // ------------------------------------------------------------------
    // How much is withheld
    // ------------------------------------------------------------------

    @Test
    void aBeneficiaryWhoPaidEveryMonthCollectsTheFullPot() throws Exception {
        var f = setUp();
        payAllAndPayOut(f, f.c1());
        payAllAndPayOut(f, f.c2());

        payAll(f, f.c3());
        var payout = payOut(f, f.c3());

        assertThat(payout.actualAmountKobo()).isEqualTo(FULL_POT);
        assertThat(payout.arrearsWithheldKobo()).isZero();
        assertThat(payout.shortfallKobo()).isZero();
    }

    @Test
    void aBeneficiaryWhoMissedOneMonthCollectsOneContributionLess() throws Exception {
        var f = setUp();
        dMissesCycleOneThenEveryonePaysUpToTheirPayout(f);

        var payout = payOut(f, f.c3());

        assertThat(payout.actualAmountKobo()).isEqualTo(FULL_POT - AMOUNT);
        assertThat(payout.arrearsWithheldKobo()).isEqualTo(AMOUNT);
        assertThat(payout.shortfallKobo()).isZero();     // the pot was full: nobody underpaid them
    }

    @Test
    void theWithheldMoneySettlesTheClaimOnTheCycleTheyMissed() throws Exception {
        var f = setUp();
        dMissesCycleOneThenEveryonePaysUpToTheirPayout(f);

        var claim = claimOn(f.c1()).orElseThrow();
        assertThat(claim.getParticipantId()).isEqualTo(f.participantIdOf(f.p1()));
        long settledBefore = claim.getSettledAmountKobo();

        var payout = payOut(f, f.c3());

        long settledAfter = claimOn(f.c1()).orElseThrow().getSettledAmountKobo();
        assertThat(settledAfter - settledBefore).isEqualTo(payout.arrearsWithheldKobo());
        assertThat(settledAfter - settledBefore).isEqualTo(AMOUNT);
    }

    // ------------------------------------------------------------------
    // What is and is not a claim
    // ------------------------------------------------------------------

    @Test
    void aWithheldPayoutRaisesAClaimOnlyForTheGenuineShortfall() throws Exception {
        var f = setUp();
        payAllExcept(f, f.c1(), f.d());
        payOut(f, f.c1());
        payAllAndPayOut(f, f.c2());

        // Cycle 3: P2 misses. The pot is one share short (the group underpaid D),
        // and D's own arrears are withheld on top of that.
        payAllExcept(f, f.c3(), f.p2());
        var payout = payOut(f, f.c3());

        assertThat(payout.actualAmountKobo()).isEqualTo(FULL_POT - AMOUNT - AMOUNT);
        assertThat(payout.arrearsWithheldKobo()).isEqualTo(AMOUNT);
        assertThat(payout.shortfallKobo()).isEqualTo(AMOUNT);

        var claim = claimOn(f.c3()).orElseThrow();
        assertThat(claim.getParticipantId()).isEqualTo(f.participantIdOf(f.d()));
        assertThat(claim.getAmountKobo())
                .as("P2's missing share only — never the arrears withheld from D")
                .isEqualTo(AMOUNT);
    }

    @Test
    void arrearsExceedingThePotLeaveAZeroPayoutAndDistributeEverything() throws Exception {
        var f = setUp();
        payAllExcept(f, f.c1(), f.d());
        payOut(f, f.c1());                               // P1 short by D's share
        payAllExcept(f, f.c2(), f.d());
        payOut(f, f.c2());                               // P2 short by D's share

        payAllExcept(f, f.c3(), f.p2());                 // pot is 2 shares; D owes 2 shares
        var payout = payOut(f, f.c3());

        assertThat(payout.actualAmountKobo()).isZero();
        assertThat(payout.arrearsWithheldKobo()).isEqualTo(2 * AMOUNT);
        assertThat(payout.ledgerTransactionId()).isNull();          // nothing paid, nothing posted
        assertThat(cycleRepository.findById(f.c3().getId()).orElseThrow().getStatus())
                .isEqualTo(CycleStatus.PAID);

        assertThat(shortfallSettlementRepository.sumAmountKoboByFundedByCycleId(f.c3().getId()))
                .isEqualTo(2 * AMOUNT);
        assertFullySettled(claimOn(f.c1()).orElseThrow());
        assertFullySettled(claimOn(f.c2()).orElseThrow());
    }

    // ------------------------------------------------------------------
    // Where withheld money goes when nothing matches
    // ------------------------------------------------------------------

    @Test
    void withheldMoneyWithNoMatchingClaimFallsThroughToTheRoundsOtherClaims() throws Exception {
        var f = setUp();
        // Cycle 1 is never paid out, so the cycle D misses has no claim to settle.
        // Nobody pays into it either: any money sitting in the pool would cover
        // cycle 2's shortfall below and there would be no claim to fall through to.

        // Cycle 2: P1 misses, so P2 is paid short and holds a claim on a cycle D paid.
        // (P2 also has cycle 1 arrears, withheld here with nowhere to go but the pool.)
        payAllExcept(f, f.c2(), f.p1());
        payOut(f, f.c2());
        var p2Claim = claimOn(f.c2()).orElseThrow();
        long settledBefore = p2Claim.getSettledAmountKobo();

        payAll(f, f.c3());
        var payout = payOut(f, f.c3());

        assertThat(payout.arrearsWithheldKobo()).isEqualTo(AMOUNT);
        assertThat(claimOn(f.c1())).isEmpty();
        assertThat(claimOn(f.c2()).orElseThrow().getSettledAmountKobo() - settledBefore)
                .isEqualTo(AMOUNT);
        assertThat(shortfallSettlementRepository.findByClaimId(p2Claim.getId()))
                .extracting(ShortfallSettlement::getFundedByCycleId)
                .contains(f.c3().getId());
    }

    @Test
    void withheldMoneyWithNoOpenClaimsAnywhereStaysInThePool() throws Exception {
        var f = setUp();
        payAllExcept(f, f.c1(), f.d());                  // cycle 1 collected into but not paid out
        payAllAndPayOut(f, f.c2());

        payAll(f, f.c3());
        var payout = payOut(f, f.c3());

        assertThat(payout.arrearsWithheldKobo()).isEqualTo(AMOUNT);
        assertThat(shortfallClaimRepository.findOpenByRoundIdOldestFirst(f.roundId())).isEmpty();
        // Cycle 1's unpaid pot, plus what was withheld from D.
        assertThat(poolBalance(f.roundId())).isEqualTo(2 * AMOUNT + AMOUNT);
    }

    // ------------------------------------------------------------------
    // The invariant, with a defaulter in the middle of it
    // ------------------------------------------------------------------

    @Test
    void aRoundWithADefaulterStillNetsToZeroForEveryParticipant() throws Exception {
        var f = setUp();
        long cashBefore = platformCash();

        // D misses month 1, pays months 2 and 3, and collects in month 3.
        payAllExcept(f, f.c1(), f.d());
        payOut(f, f.c1());
        payAllAndPayOut(f, f.c2());
        payAll(f, f.c3());
        var dPayout = payOut(f, f.c3());

        assertThat(dPayout.arrearsWithheldKobo()).isEqualTo(AMOUNT);
        assertThat(roundRepository.findById(f.roundId()).orElseThrow().getStatus())
                .isEqualTo(RoundStatus.COMPLETED);

        assertEveryParticipantSquare(f);
        assertThat(shortfallClaimRepository.findOpenByRoundIdOldestFirst(f.roundId())).isEmpty();
        assertThat(poolBalance(f.roundId())).isZero();
        assertThat(platformCash()).isEqualTo(cashBefore);
    }

    @Test
    void aMemberWhoPaysNothingCollectsNothingAndIsOwedNothing() throws Exception {
        // Regression guard: their own missing share must not raise a claim in their favour.
        var f = setUp();
        long cashBefore = platformCash();

        payAllExcept(f, f.c1(), f.d());
        payOut(f, f.c1());
        payAllExcept(f, f.c2(), f.d());
        payOut(f, f.c2());
        payAllExcept(f, f.c3(), f.d());
        var dPayout = payOut(f, f.c3());

        assertThat(dPayout.actualAmountKobo()).isZero();
        assertThat(dPayout.arrearsWithheldKobo()).isEqualTo(2 * AMOUNT);
        assertThat(dPayout.shortfallKobo()).isZero();

        assertThat(claimOn(f.c3())).as("no claim on the cycle D collected").isEmpty();
        assertThat(shortfallClaimRepository.findByParticipantId(f.participantIdOf(f.d())))
                .as("no claim anywhere in D's favour")
                .isEmpty();
        assertFullySettled(claimOn(f.c1()).orElseThrow());
        assertFullySettled(claimOn(f.c2()).orElseThrow());

        assertThat(roundRepository.findById(f.roundId()).orElseThrow().getStatus())
                .isEqualTo(RoundStatus.COMPLETED);
        assertEveryParticipantSquare(f);
        assertThat(poolBalance(f.roundId())).isZero();
        assertThat(platformCash()).isEqualTo(cashBefore);
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    /** Contributed equals collected plus claim settlements received, for every slot. */
    private void assertEveryParticipantSquare(Fixture f) {
        for (UUID participantId : f.participantIdByUserId().values()) {
            long contributed = contributionRepository.sumAmountKoboByParticipantId(participantId);
            long collected = payoutRepository.findByParticipantId(participantId).stream()
                    .mapToLong(Payout::getActualAmountKobo)
                    .sum();
            long settlements = shortfallSettlementRepository.sumAmountKoboByClaimParticipantId(participantId);

            assertThat(collected + settlements)
                    .as("participant %s: collected + settlements vs contributed", participantId)
                    .isEqualTo(contributed);
        }
    }

    private void assertFullySettled(ShortfallClaim claim) {
        assertThat(claim.getSettledAmountKobo()).isEqualTo(claim.getAmountKobo());
        assertThat(claim.getSettledAt()).isNotNull();
    }

    // ------------------------------------------------------------------
    // Round-level helpers
    // ------------------------------------------------------------------

    /** D misses cycle 1 (P1 is paid short), then cycles 2 and 3 are paid in full; cycle 2 is paid out. */
    private void dMissesCycleOneThenEveryonePaysUpToTheirPayout(Fixture f) throws Exception {
        payAllExcept(f, f.c1(), f.d());
        payOut(f, f.c1());
        payAllAndPayOut(f, f.c2());
        payAll(f, f.c3());
    }

    private void payAllAndPayOut(Fixture f, Cycle cycle) throws Exception {
        payAll(f, cycle);
        payOut(f, cycle);
    }

    private void payAll(Fixture f, Cycle cycle) throws Exception {
        payAllExcept(f, cycle, null);
    }

    private void payAllExcept(Fixture f, Cycle cycle, TestUser skipper) throws Exception {
        for (TestUser member : f.members()) {
            if (skipper != null && member.id().equals(skipper.id())) continue;
            client.contribute(member, cycle.getId(), AMOUNT, member.id(), newKey());
        }
    }

    private PayoutSummary payOut(Fixture f, Cycle cycle) throws Exception {
        return client.payout(f.admin(), cycle.getId(), f.beneficiaryOf(cycle).id(), newKey());
    }

    private Optional<ShortfallClaim> claimOn(Cycle cycle) {
        return shortfallClaimRepository.findByCycleId(cycle.getId());
    }

    /** What the pool holds, as a positive number (the ledger stores it as a liability). */
    private long poolBalance(UUID roundId) {
        var pool = ledgerAccountRepository
                .findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, roundId)
                .orElseThrow();
        return -ledgerEntryRepository.sumAmountKoboByAccountId(pool.getId());
    }

    private long platformCash() {
        return ledgerEntryRepository.sumAmountKoboByAccountId(LedgerAccounts.PLATFORM_CASH_ID);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------
    // Setup — three members, one round, activated
    // ------------------------------------------------------------------

    private Fixture setUp() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var eze = client.registerUser("Eze");
        var members = List.of(admin, ada, eze);

        var groupId = client.createGroup(admin, "Alice's Ajo");
        client.addToGroup(admin, groupId, ada);
        client.addToGroup(admin, groupId, eze);

        var roundId = client.createRound(admin, groupId, AMOUNT, PAST_START);
        for (TestUser member : members) {
            client.addParticipant(admin, roundId, member);
        }

        var detail = client.activate(admin, roundId);

        Map<UUID, TestUser> usersById = members.stream()
                .collect(Collectors.toMap(TestUser::id, Function.identity()));
        Map<UUID, UUID> participantIdByUserId = detail.participants().stream()
                .collect(Collectors.toMap(p -> p.user().id(), ParticipantSummary::id));
        Map<UUID, TestUser> userByParticipantId = detail.participants().stream()
                .collect(Collectors.toMap(ParticipantSummary::id, p -> usersById.get(p.user().id())));

        var cycles = cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId);

        return new Fixture(roundId, admin, members, cycles, participantIdByUserId, userByParticipantId);
    }
}
