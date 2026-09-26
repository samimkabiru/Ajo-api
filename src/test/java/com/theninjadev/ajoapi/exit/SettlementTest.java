package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.ledger.AccountType;
import com.theninjadev.ajoapi.ledger.LedgerAccountRepository;
import com.theninjadev.ajoapi.ledger.LedgerAccounts;
import com.theninjadev.ajoapi.ledger.LedgerEntry;
import com.theninjadev.ajoapi.ledger.LedgerEntryRepository;
import com.theninjadev.ajoapi.payout.ShortfallClaimRepository;
import com.theninjadev.ajoapi.payout.ShortfallSettlementRepository;
import com.theninjadev.ajoapi.round.Cycle;
import com.theninjadev.ajoapi.round.CycleRepository;
import com.theninjadev.ajoapi.round.CycleStatus;
import com.theninjadev.ajoapi.round.ParticipantStatus;
import com.theninjadev.ajoapi.round.ParticipantSummary;
import com.theninjadev.ajoapi.round.RoundDetail;
import com.theninjadev.ajoapi.round.RoundParticipantRepository;
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
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SettlementTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;          // ₦10,000 per member per cycle
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CycleRepository cycleRepository;
    @Autowired private RoundParticipantRepository roundParticipantRepository;
    @Autowired private ExitRequestRepository exitRequestRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private ShortfallClaimRepository shortfallClaimRepository;
    @Autowired private ShortfallSettlementRepository shortfallSettlementRepository;
    @Autowired private LedgerAccountRepository ledgerAccountRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper, userRepository);
    }

    private record Fixture(UUID roundId,
                           TestUser admin,
                           List<TestUser> members,
                           Map<UUID, UUID> participantIdByUserId,
                           Map<UUID, TestUser> userByParticipantId) {

        UUID participantIdOf(TestUser user) {
            return participantIdByUserId.get(user.id());
        }

        TestUser beneficiaryOf(Cycle cycle) {
            return userByParticipantId.get(cycle.getBeneficiaryId());
        }

        List<TestUser> membersOtherThan(TestUser user) {
            return members.stream().filter(m -> !m.id().equals(user.id())).toList();
        }
    }

    /** A leaver who contributed once, is owed it back, and whose cycle is now VACANT. */
    private record Vacated(Fixture fixture, TestUser leaver, UUID exitId, UUID vacantCycleId) {}

    // The scenario this whole project was built around

    @Test
    void theVacantPotCoversTheRefundAndTheShortfallExactly() throws Exception {
        // Ada, Bola and Chidi, ₦10,000 a month, three months.
        // Chidi pays month 1, then leaves. Nobody buys in.
        var f = setUp();
        var cycles = cycles(f);

        long cashAtStart = platformCash();

        // --- Month 1: everyone pays, its beneficiary collects the full pot.
        var monthOne = cycles.get(0);
        var firstCollector = f.beneficiaryOf(monthOne);
        contributeAll(f, monthOne);
        client.payout(f.admin(), monthOne.getId(), firstCollector.id(), newKey());

        // Chidi is whoever collects last — he leaves before his turn.
        var chidi = f.beneficiaryOf(cycles.get(2));
        var exit = requestExit(chidi, f.roundId());
        assertThat(exit.status()).isEqualTo(ExitStatus.PENDING_SETTLEMENT);   // he is owed his month 1

        var remaining = f.membersOtherThan(chidi);

        // --- Month 2: only the two who are left pay, so its beneficiary is short ₦10,000.
        var monthTwo = cycles.get(1);
        for (TestUser member : remaining) {
            client.contribute(member, monthTwo.getId(), AMOUNT, member.id(), newKey());
        }
        var secondCollector = f.beneficiaryOf(monthTwo);
        var shortPayout = client.payout(f.admin(), monthTwo.getId(), secondCollector.id(), newKey());

        assertThat(shortPayout.actualAmountKobo()).isEqualTo(2 * AMOUNT);
        assertThat(shortPayout.shortfallKobo()).isEqualTo(AMOUNT);

        // --- Month 3: Chidi's cycle. Vacant. The two remaining pay into it and nobody collects.
        var monthThree = cycleRepository.findById(cycles.get(2).getId()).orElseThrow();
        assertThat(monthThree.getStatus()).isEqualTo(CycleStatus.SCHEDULED);
        assertThat(monthThree.getBeneficiaryId()).isEqualTo(f.participantIdOf(chidi));
        for (TestUser member : remaining) {
            client.contribute(member, monthThree.getId(), AMOUNT, member.id(), newKey());
        }

        // --- Settlement: ₦20,000 unclaimed, funding a ₦10,000 refund and a ₦10,000 claim.
        var settlement = settle(f.admin(), monthThree.getId());

        assertThat(cycleRepository.findById(monthThree.getId()).orElseThrow().getStatus())
                .isEqualTo(CycleStatus.SETTLED);
        assertThat(settlement.potKobo()).isEqualTo(2 * AMOUNT);
        assertThat(settlement.refund()).isNotNull();
        assertThat(settlement.refund().actualAmountKobo()).isEqualTo(AMOUNT);
        assertThat(settlement.refund().expectedAmountKobo()).isEqualTo(AMOUNT);
        assertThat(settlement.refund().recipient().id()).isEqualTo(chidi.id());
        assertThat(settlement.claimsSettled()).hasSize(1);
        assertThat(settlement.remainingKobo()).isZero();
        assertThat(settlement.cycleStatus()).isEqualTo(CycleStatus.SETTLED);

        // The claim is fully paid.
        var claim = shortfallClaimRepository.findByCycleId(monthTwo.getId()).orElseThrow();
        assertThat(claim.getSettledAmountKobo()).isEqualTo(claim.getAmountKobo());
        assertThat(claim.getSettledAt()).isNotNull();

        // Chidi is out, and square.
        assertThat(exitRequestRepository.findById(exit.id()).orElseThrow().getStatus())
                .isEqualTo(ExitStatus.COMPLETED);
        assertThat(roundParticipantRepository.findById(f.participantIdOf(chidi)).orElseThrow().getStatus())
                .isEqualTo(ParticipantStatus.EXITED);
        assertThat(exposure(f.admin(), f.participantIdOf(chidi))).isZero();

        // Everyone else is square too, and the system holds nothing back.
        for (TestUser member : remaining) {
            assertThat(exposure(f.admin(), f.participantIdOf(member)))
                    .as("exposure for %s", member.id())
                    .isZero();
        }
        assertThat(poolBalance(f.roundId())).isZero();
        assertThat(platformCash()).isEqualTo(cashAtStart);
    }

    // The pieces, individually

    @Test
    void settlementRefundsTheLeaverFromTheVacantPot() throws Exception {
        var v = vacatedCycleWithPot();

        var settlement = settle(v.fixture().admin(), v.vacantCycleId());

        assertThat(settlement.refund()).isNotNull();
        assertThat(settlement.refund().actualAmountKobo()).isEqualTo(AMOUNT);
        assertThat(settlement.refund().recipient().id()).isEqualTo(v.leaver().id());

        var refund = refundRepository.findByExitRequestId(v.exitId()).orElseThrow();
        assertThat(refund.getCycleId()).isEqualTo(v.vacantCycleId());
        assertThat(refund.getUserId()).isEqualTo(v.leaver().id());
    }

    @Test
    void aFullyRefundedLeaverIsExitedAndSquare() throws Exception {
        var v = vacatedCycleWithPot();
        var f = v.fixture();

        settle(f.admin(), v.vacantCycleId());

        assertThat(exitRequestRepository.findById(v.exitId()).orElseThrow().getStatus())
                .isEqualTo(ExitStatus.COMPLETED);
        assertThat(roundParticipantRepository.findById(f.participantIdOf(v.leaver())).orElseThrow().getStatus())
                .isEqualTo(ParticipantStatus.EXITED);
        assertThat(exposure(f.admin(), f.participantIdOf(v.leaver()))).isZero();
    }

    @Test
    void settlementPostsBalancedEntriesForEveryDistribution() throws Exception {
        var v = vacatedCycleWithPot();
        var f = v.fixture();

        var settlement = settle(f.admin(), v.vacantCycleId());

        var refund = refundRepository.findByExitRequestId(v.exitId()).orElseThrow();
        var entries = ledgerEntryRepository.findByTransactionId(refund.getLedgerTransactionId());

        assertThat(entries).hasSize(2);
        assertThat(entries.stream().mapToLong(LedgerEntry::getAmountKobo).sum()).isZero();

        // Cash leaves the platform when a refund is paid.
        var cashEntry = entries.stream()
                .filter(e -> e.getAccountId().equals(LedgerAccounts.PLATFORM_CASH_ID))
                .findFirst()
                .orElseThrow();
        assertThat(cashEntry.getAmountKobo()).isEqualTo(-settlement.refund().actualAmountKobo());
    }

    @Test
    void moneyLeftAfterEverythingIsPaidStaysInThePool() throws Exception {
        var v = vacatedCycleWithPot();      // pot is 2 x AMOUNT, refund owed is AMOUNT, no claims
        var f = v.fixture();

        var settlement = settle(f.admin(), v.vacantCycleId());

        assertThat(settlement.remainingKobo()).isEqualTo(AMOUNT);
        assertThat(poolBalance(f.roundId())).isEqualTo(-2 * AMOUNT);   // still a liability
    }

    @Test
    void aSettledCycleCannotBeSettledAgain() throws Exception {
        var v = vacatedCycleWithPot();
        var f = v.fixture();

        settle(f.admin(), v.vacantCycleId());

        // Nothing outstanding anywhere, so the cycle reached SETTLED.
        assertThat(cycleRepository.findById(v.vacantCycleId()).orElseThrow().getStatus())
                .isEqualTo(CycleStatus.SETTLED);

        settleAndExpect(f.admin(), v.vacantCycleId(), 409);
    }

    @Test
    void aSettledCycleNoLongerAcceptsContributions() throws Exception {
        var v = vacatedCycleWithPot();
        var f = v.fixture();

        settle(f.admin(), v.vacantCycleId());

        var payer = f.membersOtherThan(v.leaver()).getFirst();
        contributeAndExpect(payer, v.vacantCycleId(), 409);
    }

    @Test
    void settlingTwiceOnlyDistributesThePotOnce() throws Exception {
        var v = vacatedCycleWithPot();
        var f = v.fixture();

        settle(f.admin(), v.vacantCycleId());
        long distributed = shortfallSettlementRepository.sumAmountKoboByFundedByCycleId(v.vacantCycleId());
        long refunded = refundRepository.findByCycleId(v.vacantCycleId()).stream()
                .mapToLong(Refund::getActualAmountKobo)
                .sum();

        settleAndExpect(f.admin(), v.vacantCycleId(), 409);

        assertThat(shortfallSettlementRepository.sumAmountKoboByFundedByCycleId(v.vacantCycleId()))
                .isEqualTo(distributed);
        assertThat(refundRepository.findByCycleId(v.vacantCycleId())).hasSize(1);
        assertThat(refundRepository.findByCycleId(v.vacantCycleId()).stream()
                .mapToLong(Refund::getActualAmountKobo).sum()).isEqualTo(refunded);
    }

    // Validation

    @Test
    void anOccupiedCycleCannotBeSettled() throws Exception {
        var f = setUp();
        var cycle = cycles(f).getFirst();

        settleAndExpect(f.admin(), cycle.getId(), 409);
    }

    @Test
    void anEmptyVacantCycleHasNothingToSettle() throws Exception {
        var f = setUp();
        var leaver = f.members().getFirst();               // zero exposure: exits at once

        requestExit(leaver, f.roundId());
        var vacant = vacantCycleOf(f);

        // Nobody has contributed to it, so there is no pot.
        settleAndExpect(f.admin(), vacant.getId(), 409);
    }

    @Test
    void aVacantCycleCannotBeSettledBeforeItsPayoutDate() throws Exception {
        var f = setUp(LocalDate.now().plusMonths(6).withDayOfMonth(28));
        var leaver = f.members().getFirst();

        requestExit(leaver, f.roundId());
        var vacant = vacantCycleOf(f);

        settleAndExpect(f.admin(), vacant.getId(), 409);
    }

    @Test
    void onlyAnAdminCanSettleAVacantCycle() throws Exception {
        var v = vacatedCycleWithPot();
        var f = v.fixture();
        var member = f.membersOtherThan(v.leaver()).stream()
                .filter(m -> !m.id().equals(f.admin().id()))
                .findFirst()
                .orElseThrow();

        settleAndExpect(member, v.vacantCycleId(), 403);
    }

    @Test
    void aNonGroupMemberCannotSettle() throws Exception {
        var v = vacatedCycleWithPot();
        var outsider = client.registerUser("Outsider");

        settleAndExpect(outsider, v.vacantCycleId(), 404);
    }

    // Setup helpers

    /**
     * A leaver who contributed once and left before collecting, whose now-vacant cycle
     * has been funded by the two remaining members. Pot 2 x AMOUNT, refund owed AMOUNT.
     */
    private Vacated vacatedCycleWithPot() throws Exception {
        var f = setUp();
        var cycles = cycles(f);

        // The leaver collects last, so their cycle is still ahead of them.
        var leaver = f.beneficiaryOf(cycles.getLast());
        var leaverCycleId = cycles.getLast().getId();

        client.contribute(leaver, cycles.getFirst().getId(), AMOUNT, leaver.id(), newKey());
        var exit = requestExit(leaver, f.roundId());

        for (TestUser member : f.membersOtherThan(leaver)) {
            client.contribute(member, leaverCycleId, AMOUNT, member.id(), newKey());
        }

        return new Vacated(f, leaver, exit.id(), leaverCycleId);
    }

    private Cycle vacantCycleOf(Fixture f) {
        return cycles(f).stream()
                .filter(c -> c.getStatus() == CycleStatus.VACANT)
                .findFirst()
                .orElseThrow();
    }

    private void contributeAll(Fixture f, Cycle cycle) throws Exception {
        for (TestUser member : f.members()) {
            client.contribute(member, cycle.getId(), AMOUNT, member.id(), newKey());
        }
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

    private long platformCash() {
        return ledgerEntryRepository.sumAmountKoboByAccountId(LedgerAccounts.PLATFORM_CASH_ID);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    // HTTP helpers

    private SettlementSummary settle(TestUser caller, UUID cycleId) throws Exception {
        var result = mockMvc.perform(post("/cycles/" + cycleId + "/settle")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), SettlementSummary.class);
    }

    private void settleAndExpect(TestUser caller, UUID cycleId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/cycles/" + cycleId + "/settle")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    private ExitRequestSummary requestExit(TestUser caller, UUID roundId) throws Exception {
        var result = mockMvc.perform(post("/rounds/" + roundId + "/exit")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ExitRequestSummary.class);
    }

    private long exposure(TestUser caller, UUID participantId) throws Exception {
        return client.getExposure(caller, participantId).exposureKobo();
    }

    private void contributeAndExpect(TestUser caller, UUID cycleId, int expectedStatus) throws Exception {
        client.contributeAndExpect(caller, cycleId, AMOUNT, caller.id(), newKey(), expectedStatus);
    }

    // Setup — three members, one round, activated

    private Fixture setUp() throws Exception {
        return setUp(PAST_START);
    }

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

        return new Fixture(roundId, admin, members, participantIdByUserId, userByParticipantId);
    }
}