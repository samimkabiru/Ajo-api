package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.contribution.Contribution;
import com.theninjadev.ajoapi.contribution.ContributionRepository;
import com.theninjadev.ajoapi.ledger.AccountType;
import com.theninjadev.ajoapi.ledger.LedgerAccountRepository;
import com.theninjadev.ajoapi.ledger.LedgerAccounts;
import com.theninjadev.ajoapi.ledger.LedgerEntry;
import com.theninjadev.ajoapi.ledger.LedgerEntryRepository;
import com.theninjadev.ajoapi.payout.Payout;
import com.theninjadev.ajoapi.payout.PayoutRepository;
import com.theninjadev.ajoapi.round.Cycle;
import com.theninjadev.ajoapi.round.CycleRepository;
import com.theninjadev.ajoapi.round.CycleStatus;
import com.theninjadev.ajoapi.round.ParticipantStatus;
import com.theninjadev.ajoapi.round.ParticipantSummary;
import com.theninjadev.ajoapi.round.RoundDetail;
import com.theninjadev.ajoapi.round.RoundParticipant;
import com.theninjadev.ajoapi.round.RoundParticipantRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BuyInTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;          // ₦10,000 per member per cycle
    private static final long FULL_POT = 3 * AMOUNT;         // three participants
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CycleRepository cycleRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private RoundParticipantRepository roundParticipantRepository;
    @Autowired private ExitRequestRepository exitRequestRepository;
    @Autowired private BuyInRepository buyInRepository;
    @Autowired private ContributionRepository contributionRepository;
    @Autowired private PayoutRepository payoutRepository;
    @Autowired private LedgerAccountRepository ledgerAccountRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper);
    }

    /**
     * Four group members, three of them in the round. The fourth is the standby
     * replacement — in the group, deliberately not a participant.
     */
    private record Fixture(UUID roundId,
                           TestUser admin,
                           List<TestUser> participants,
                           TestUser standby,
                           Map<UUID, UUID> participantIdByUserId,
                           Map<UUID, TestUser> userByParticipantId) {

        UUID participantIdOf(TestUser user) {
            return participantIdByUserId.get(user.id());
        }

        TestUser beneficiaryOf(Cycle cycle) {
            return userByParticipantId.get(cycle.getBeneficiaryId());
        }

        List<TestUser> participantsOtherThan(TestUser user) {
            return participants.stream().filter(p -> !p.id().equals(user.id())).toList();
        }
    }

    /** A leaver who has contributed once and is owed it back, with an open exit. */
    private record PendingExit(Fixture fixture, TestUser leaver, UUID exitId, UUID participantId, UUID cycleId) {}

    // ------------------------------------------------------------------
    // The slot transfer
    // ------------------------------------------------------------------

    @Test
    void theSlotPassesToTheReplacement() throws Exception {
        var p = pendingExit();
        var f = p.fixture();

        buyIn(f.standby(), p.exitId(), f.standby().id(), AMOUNT);

        var slot = roundParticipantRepository.findById(p.participantId()).orElseThrow();
        assertThat(slot.getUserId()).isEqualTo(f.standby().id());
    }

    @Test
    void theReplacementInheritsThePositionAndTheCycle() throws Exception {
        var p = pendingExit();
        var f = p.fixture();
        int positionBefore = roundParticipantRepository.findById(p.participantId())
                .orElseThrow().getPayoutPosition();

        buyIn(f.standby(), p.exitId(), f.standby().id(), AMOUNT);

        var slot = roundParticipantRepository.findById(p.participantId()).orElseThrow();
        assertThat(slot.getPayoutPosition()).isEqualTo(positionBefore);

        var cycle = cycleRepository.findById(p.cycleId()).orElseThrow();
        assertThat(cycle.getBeneficiaryId()).isEqualTo(p.participantId());
        assertThat(cycle.getStatus()).isNotEqualTo(CycleStatus.VACANT);
    }

    @Test
    void theReplacementInheritsTheContributionHistory() throws Exception {
        var p = pendingExit();
        var f = p.fixture();

        buyIn(f.standby(), p.exitId(), f.standby().id(), AMOUNT);

        // The leaver's one contribution now belongs to the replacement's slot —
        // which is exactly what they paid for.
        long contributed = contributionRepository.sumAmountKoboByParticipantId(p.participantId());
        assertThat(contributed).isEqualTo(AMOUNT);
    }

    @Test
    void theTransferredSlotIsActiveNotLeaving() throws Exception {
        var p = pendingExit();
        var f = p.fixture();

        buyIn(f.standby(), p.exitId(), f.standby().id(), AMOUNT);

        var slot = roundParticipantRepository.findById(p.participantId()).orElseThrow();
        assertThat(slot.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
    }

    @Test
    void theLeaverNoLongerHasAParticipantRowInTheRound() throws Exception {
        var p = pendingExit();
        var f = p.fixture();

        buyIn(f.standby(), p.exitId(), f.standby().id(), AMOUNT);

        assertThat(roundParticipantRepository.findByRoundIdAndUserId(f.roundId(), p.leaver().id()))
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // The money
    // ------------------------------------------------------------------

    @Test
    void aBuyInPostsTwoBalancedTransactions() throws Exception {
        var p = pendingExit();
        var f = p.fixture();

        var buyIn = buyIn(f.standby(), p.exitId(), f.standby().id(), AMOUNT);

        assertThat(buyIn.buyInTransactionId()).isNotEqualTo(buyIn.refundTransactionId());

        var moneyIn = ledgerEntryRepository.findByTransactionId(buyIn.buyInTransactionId());
        var moneyOut = ledgerEntryRepository.findByTransactionId(buyIn.refundTransactionId());

        assertThat(moneyIn).hasSize(2);
        assertThat(moneyOut).hasSize(2);
        assertThat(moneyIn.stream().mapToLong(LedgerEntry::getAmountKobo).sum()).isZero();
        assertThat(moneyOut.stream().mapToLong(LedgerEntry::getAmountKobo).sum()).isZero();
    }

    @Test
    void aBuyInLeavesThePoolAndPlatformCashExactlyWhereTheyWere() throws Exception {
        var p = pendingExit();
        var f = p.fixture();

        long poolBefore = poolBalance(f.roundId());
        long cashBefore = ledgerEntryRepository.sumAmountKoboByAccountId(LedgerAccounts.PLATFORM_CASH_ID);

        buyIn(f.standby(), p.exitId(), f.standby().id(), AMOUNT);

        // Money in from the replacement, straight back out to the leaver.
        assertThat(poolBalance(f.roundId())).isEqualTo(poolBefore);
        assertThat(ledgerEntryRepository.sumAmountKoboByAccountId(LedgerAccounts.PLATFORM_CASH_ID))
                .isEqualTo(cashBefore);
    }

    @Test
    void theBuyInRecordsWhoLeftAndWhoArrived() throws Exception {
        var p = pendingExit();
        var f = p.fixture();

        var buyIn = buyIn(f.standby(), p.exitId(), f.standby().id(), AMOUNT);

        // Read from the snapshot: the participant belongs to the replacement now,
        // so it can no longer tell us who left.
        assertThat(buyIn.leaver().id()).isEqualTo(p.leaver().id());
        assertThat(buyIn.replacement().id()).isEqualTo(f.standby().id());
        assertThat(buyIn.amountKobo()).isEqualTo(AMOUNT);
    }

    // ------------------------------------------------------------------
    // The exit
    // ------------------------------------------------------------------

    @Test
    void theExitCompletesWithoutVacatingTheCycle() throws Exception {
        var p = pendingExit();
        var f = p.fixture();

        buyIn(f.standby(), p.exitId(), f.standby().id(), AMOUNT);

        assertThat(exitRequestRepository.findById(p.exitId()).orElseThrow().getStatus())
                .isEqualTo(ExitStatus.COMPLETED);

        var cycle = cycleRepository.findById(p.cycleId()).orElseThrow();
        assertThat(cycle.getStatus()).isNotEqualTo(CycleStatus.VACANT);
        assertThat(cycle.getBeneficiaryId()).isNotNull();
    }

    @Test
    void aBuyInLeavesVacatedByExitIdNull() throws Exception {
        // The slot survives the exit, so no cycle was vacated and nothing points back at it.
        var p = pendingExit();
        var f = p.fixture();

        buyIn(f.standby(), p.exitId(), f.standby().id(), AMOUNT);

        assertThat(cycleRepository.findById(p.cycleId()).orElseThrow().getVacatedByExitId()).isNull();
        assertThat(cycleRepository.findByVacatedByExitId(p.exitId())).isEmpty();
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    void theAmountMustMatchWhatTheLeaverContributed() throws Exception {
        var p = pendingExit();
        var f = p.fixture();

        buyInAndExpect(f.standby(), p.exitId(), f.standby().id(), AMOUNT * 2, 400);
    }

    @Test
    void aSlotWhoseOwnerOwesTheGroupCannotBeBoughtInto() throws Exception {
        var f = setUp();
        var cycle = cycles(f).getFirst();
        var debtor = f.beneficiaryOf(cycle);

        // They collect the pot having paid one month, so they owe the difference.
        for (TestUser member : f.participants()) {
            client.contribute(member, cycle.getId(), AMOUNT, member.id(), newKey());
        }
        client.payout(f.admin(), cycle.getId(), debtor.id(), newKey());

        var exit = requestExit(debtor, f.roundId());

        buyInAndExpect(f.standby(), exit.id(), f.standby().id(), FULL_POT - AMOUNT, 409);
    }

    @Test
    void aSlotWithNothingOwedCannotBeBoughtInto() throws Exception {
        var f = setUp();
        var leaver = f.participants().getFirst();          // has neither paid nor collected

        // Zero exposure completes the exit outright, so there is nothing left to buy.
        var exit = requestExit(leaver, f.roundId());

        buyInAndExpect(f.standby(), exit.id(), f.standby().id(), AMOUNT, 409);
    }

    @Test
    void aReplacementWhoIsAlreadyInTheRoundIsRejected() throws Exception {
        var p = pendingExit();
        var f = p.fixture();
        var insider = f.participantsOtherThan(p.leaver()).getFirst();

        buyInAndExpect(f.admin(), p.exitId(), insider.id(), AMOUNT, 409);
    }

    @Test
    void aReplacementWhoIsNotInTheGroupIsRejected() throws Exception {
        var p = pendingExit();
        var f = p.fixture();
        var outsider = client.registerUser("Outsider");

        buyInAndExpect(f.admin(), p.exitId(), outsider.id(), AMOUNT, 400);
    }

    @Test
    void someoneWhoIsNeitherTheReplacementNorAnAdminCannotRecordABuyIn() throws Exception {
        var p = pendingExit();
        var f = p.fixture();
        var bystander = f.participantsOtherThan(p.leaver()).stream()
                .filter(m -> !m.id().equals(f.admin().id()))
                .findFirst()
                .orElseThrow();

        buyInAndExpect(bystander, p.exitId(), f.standby().id(), AMOUNT, 403);
    }

    @Test
    void anExitCanOnlyBeSettledOnce() throws Exception {
        var p = pendingExit();
        var f = p.fixture();
        var secondStandby = client.registerUser("Second standby");
        client.addToGroup(f.admin(), groupOf(f), secondStandby);

        buyIn(f.standby(), p.exitId(), f.standby().id(), AMOUNT);

        buyInAndExpect(f.admin(), p.exitId(), secondStandby.id(), AMOUNT, 409);
    }

    @Test
    void theSameIdempotencyKeyCreatesOneBuyInAndTwoPostings() throws Exception {
        var p = pendingExit();
        var f = p.fixture();
        var key = newKey();

        var first = buyIn(f.standby(), p.exitId(), f.standby().id(), AMOUNT, key);
        var second = buyIn(f.standby(), p.exitId(), f.standby().id(), AMOUNT, key);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.buyInTransactionId()).isEqualTo(first.buyInTransactionId());
        assertThat(second.refundTransactionId()).isEqualTo(first.refundTransactionId());
        assertThat(buyInRepository.findByRoundId(f.roundId())).hasSize(1);

        // Two postings for the buy-in, not four.
        assertThat(ledgerEntryRepository.findByTransactionId(first.buyInTransactionId())).hasSize(2);
        assertThat(ledgerEntryRepository.findByTransactionId(first.refundTransactionId())).hasSize(2);
    }

    // ------------------------------------------------------------------
    // End to end — the invariant survives a change of hands
    // ------------------------------------------------------------------

    @Test
    void aRoundWithABuyInStillNetsToZeroForEverySlot() throws Exception {
        var p = pendingExit();
        var f = p.fixture();

        long cashBefore = ledgerEntryRepository.sumAmountKoboByAccountId(LedgerAccounts.PLATFORM_CASH_ID)
                - AMOUNT;   // the leaver's one contribution is already in

        buyIn(f.standby(), p.exitId(), f.standby().id(), AMOUNT);

        // The round runs to its end with the replacement in the leaver's slot.
        for (Cycle cycle : cycles(f)) {
            for (RoundParticipant slot : roundParticipantRepository.findByRoundId(f.roundId())) {
                if (contributionRepository.findByParticipantId(slot.getId()).stream()
                        .anyMatch(c -> c.getCycleId().equals(cycle.getId()))) {
                    continue;                                   // already paid this month
                }
                TestUser payer = userOf(f, slot.getUserId());
                client.contribute(payer, cycle.getId(), AMOUNT, payer.id(), newKey());
            }

            var beneficiarySlot = cycleRepository.findById(cycle.getId()).orElseThrow().getBeneficiaryId();
            TestUser beneficiary = userOf(f, roundParticipantRepository.findById(beneficiarySlot)
                    .orElseThrow().getUserId());
            client.payout(f.admin(), cycle.getId(), beneficiary.id(), newKey());
        }

        assertThat(roundRepository.findById(f.roundId()).orElseThrow().getStatus())
                .isEqualTo(RoundStatus.COMPLETED);

        // Every slot — including the one that changed hands — balances.
        for (RoundParticipant slot : roundParticipantRepository.findByRoundId(f.roundId())) {
            long contributed = contributionRepository.sumAmountKoboByParticipantId(slot.getId());
            long collected = payoutRepository.findByParticipantId(slot.getId()).stream()
                    .mapToLong(Payout::getActualAmountKobo)
                    .sum();

            assertThat(contributed).as("slot %s contributed", slot.getId()).isEqualTo(FULL_POT);
            assertThat(collected).as("slot %s collected", slot.getId()).isEqualTo(contributed);
        }

        assertThat(poolBalance(f.roundId())).isZero();
        assertThat(ledgerEntryRepository.sumAmountKoboByAccountId(LedgerAccounts.PLATFORM_CASH_ID))
                .isEqualTo(cashBefore);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Sets up a leaver who contributed once, is owed it back, and has an open exit. */
    private PendingExit pendingExit() throws Exception {
        var f = setUp();
        var cycle = cycles(f).getFirst();
        var leaver = f.beneficiaryOf(cycle);

        client.contribute(leaver, cycle.getId(), AMOUNT, leaver.id(), newKey());
        var exit = requestExit(leaver, f.roundId());

        return new PendingExit(f, leaver, exit.id(), f.participantIdOf(leaver), cycle.getId());
    }

    private List<Cycle> cycles(Fixture f) {
        return cycleRepository.findByRoundIdOrderByCycleNumberAsc(f.roundId());
    }

    private long poolBalance(UUID roundId) {
        var pool = ledgerAccountRepository
                .findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, roundId)
                .orElseThrow();
        return ledgerEntryRepository.sumAmountKoboByAccountId(pool.getId());
    }

    private TestUser userOf(Fixture f, UUID userId) {
        return f.participants().stream()
                .filter(u -> u.id().equals(userId))
                .findFirst()
                .orElseGet(() -> {
                    if (f.standby().id().equals(userId)) return f.standby();
                    throw new IllegalStateException("Unknown user in round: " + userId);
                });
    }

    private UUID groupOf(Fixture f) {
        return roundRepository.findById(f.roundId()).orElseThrow().getGroupId();
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    // HTTP helpers

    private ExitRequestSummary requestExit(TestUser caller, UUID roundId) throws Exception {
        var result = mockMvc.perform(post("/rounds/" + roundId + "/exit")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ExitRequestSummary.class);
    }

    private BuyInSummary buyIn(TestUser caller, UUID exitId, UUID replacementUserId, long amountKobo)
            throws Exception {
        return buyIn(caller, exitId, replacementUserId, amountKobo, newKey());
    }

    private BuyInSummary buyIn(TestUser caller, UUID exitId, UUID replacementUserId,
                               long amountKobo, String key) throws Exception {
        var result = mockMvc.perform(post("/exits/" + exitId + "/buy-in")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BuyInRequest(replacementUserId, amountKobo, BuyInMethod.ONLINE))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), BuyInSummary.class);
    }

    private void buyInAndExpect(TestUser caller, UUID exitId, UUID replacementUserId,
                                long amountKobo, int expectedStatus) throws Exception {
        mockMvc.perform(post("/exits/" + exitId + "/buy-in")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BuyInRequest(replacementUserId, amountKobo, BuyInMethod.ONLINE))))
                .andExpect(status().is(expectedStatus));
    }

    // Setup — four group members, three in the round, one standby

    private Fixture setUp() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var eze = client.registerUser("Eze");
        var standby = client.registerUser("Standby");
        var participants = List.of(admin, ada, eze);

        var groupId = client.createGroup(admin, "Alice's Ajo");
        client.addToGroup(admin, groupId, ada);
        client.addToGroup(admin, groupId, eze);
        client.addToGroup(admin, groupId, standby);        // in the group, not in the round

        var roundId = client.createRound(admin, groupId, AMOUNT, PAST_START);
        for (TestUser member : participants) {
            client.addParticipant(admin, roundId, member);
        }

        RoundDetail detail = client.activate(admin, roundId);

        Map<UUID, TestUser> usersById = participants.stream()
                .collect(Collectors.toMap(TestUser::id, Function.identity()));

        Map<UUID, UUID> participantIdByUserId = detail.participants().stream()
                .collect(Collectors.toMap(p -> p.user().id(), ParticipantSummary::id));

        Map<UUID, TestUser> userByParticipantId = detail.participants().stream()
                .collect(Collectors.toMap(ParticipantSummary::id, p -> usersById.get(p.user().id())));

        return new Fixture(roundId, admin, participants, standby,
                participantIdByUserId, userByParticipantId);
    }
}