package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.ledger.LedgerEntry;
import com.theninjadev.ajoapi.ledger.LedgerEntryRepository;
import com.theninjadev.ajoapi.payout.PayoutMethod;
import com.theninjadev.ajoapi.payout.PayoutRequest;
import com.theninjadev.ajoapi.round.Cycle;
import com.theninjadev.ajoapi.round.CycleRepository;
import com.theninjadev.ajoapi.round.CycleStatus;
import com.theninjadev.ajoapi.round.ParticipantStatus;
import com.theninjadev.ajoapi.round.ParticipantSummary;
import com.theninjadev.ajoapi.round.RoundDetail;
import com.theninjadev.ajoapi.round.RoundParticipant;
import com.theninjadev.ajoapi.round.RoundParticipantRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ExitTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;          // ₦10,000 per member per cycle
    private static final long FULL_POT = 3 * AMOUNT;         // three participants
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CycleRepository cycleRepository;
    @Autowired private RoundParticipantRepository roundParticipantRepository;
    @Autowired private ExitRequestRepository exitRequestRepository;
    @Autowired private RepaymentRepository repaymentRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private PositionSwapRequestRepository swapRequestRepository;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper);
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

    // requestExit — zero exposure completes immediately

    @Test
    void zeroExposureCompletesTheExitImmediately() throws Exception {
        var f = setUp();
        var leaver = f.members().getFirst();               // has neither paid nor collected

        var exit = requestExit(leaver, f.roundId());

        assertThat(exit.status()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(exit.exposureAtRequest()).isZero();
        assertThat(participant(f, leaver).getStatus()).isEqualTo(ParticipantStatus.EXITED);
    }

    @Test
    void completingAnExitVacatesAnUncollectedCycle() throws Exception {
        var f = setUp();
        var leaver = f.members().getFirst();
        UUID cycleId = cycleOf(f, leaver).getId();

        requestExit(leaver, f.roundId());

        var cycle = cycleRepository.findById(cycleId).orElseThrow();
        assertThat(cycle.getStatus()).isEqualTo(CycleStatus.VACANT);
        assertThat(cycle.getBeneficiaryId()).isNull();
    }

    @Test
    void completingAnExitLeavesAnAlreadyPaidCycleAlone() throws Exception {
        var f = setUp();
        var cycle = cycles(f).getFirst();
        var beneficiary = f.beneficiaryOf(cycle);

        // Only the beneficiary contributes, so they collect exactly what they put in
        // and end up square — a paid cycle with zero exposure.
        client.contribute(beneficiary, cycle.getId(), AMOUNT, beneficiary.id(), newKey());
        client.payout(f.admin(), cycle.getId(), beneficiary.id(), newKey());

        var exit = requestExit(beneficiary, f.roundId());

        assertThat(exit.status()).isEqualTo(ExitStatus.COMPLETED);
        var reloaded = cycleRepository.findById(cycle.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CycleStatus.PAID);
        assertThat(reloaded.getBeneficiaryId()).isEqualTo(f.participantIdOf(beneficiary));
    }

    // requestExit — non-zero exposure waits for settlement

    @Test
    void beingOwedMoneyLeavesTheExitPendingAndTheCycleUntouched() throws Exception {
        var f = setUp();
        var cycle = cycles(f).getFirst();
        var leaver = f.beneficiaryOf(cycle);

        client.contribute(leaver, cycle.getId(), AMOUNT, leaver.id(), newKey());   // exposure -AMOUNT

        var exit = requestExit(leaver, f.roundId());

        assertThat(exit.status()).isEqualTo(ExitStatus.PENDING_SETTLEMENT);
        assertThat(exit.exposureAtRequest()).isEqualTo(-AMOUNT);
        assertThat(participant(f, leaver).getStatus()).isEqualTo(ParticipantStatus.PENDING_EXIT);

        // The cycle is untouched until the exit completes, so a cancel needs no repair.
        var reloaded = cycleRepository.findById(cycle.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isNotEqualTo(CycleStatus.VACANT);
        assertThat(reloaded.getBeneficiaryId()).isEqualTo(f.participantIdOf(leaver));
    }

    @Test
    void owingMoneyLeavesTheExitPendingSettlement() throws Exception {
        var f = setUp();
        var leaver = collectorWithDebt(f);                 // collected the pot, paid one month

        var exit = requestExit(leaver, f.roundId());

        assertThat(exit.status()).isEqualTo(ExitStatus.PENDING_SETTLEMENT);
        assertThat(exit.exposureAtRequest()).isEqualTo(FULL_POT - AMOUNT);
        assertThat(participant(f, leaver).getStatus()).isEqualTo(ParticipantStatus.PENDING_EXIT);
    }

    @Test
    void aSecondExitRequestIsRejected() throws Exception {
        var f = setUp();
        var leaver = collectorWithDebt(f);

        requestExit(leaver, f.roundId());
        requestExitAndExpect(leaver, f.roundId(), 409);
    }

    @Test
    void someoneWhoIsNotInTheRoundCannotRequestAnExit() throws Exception {
        var f = setUp();
        var outsider = client.registerUser("Outsider");

        requestExitAndExpect(outsider, f.roundId(), 404);
    }

    // cancelExit

    @Test
    void cancellingAnExitRestoresTheParticipantWithCycleAndPositionIntact() throws Exception {
        var f = setUp();
        var cycle = cycles(f).getFirst();
        var leaver = f.beneficiaryOf(cycle);
        int positionBefore = participant(f, leaver).getPayoutPosition();

        client.contribute(leaver, cycle.getId(), AMOUNT, leaver.id(), newKey());
        var exit = requestExit(leaver, f.roundId());

        cancelExit(leaver, exit.id());

        var reloadedParticipant = participant(f, leaver);
        assertThat(reloadedParticipant.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
        assertThat(reloadedParticipant.getPayoutPosition()).isEqualTo(positionBefore);

        var reloadedCycle = cycleRepository.findById(cycle.getId()).orElseThrow();
        assertThat(reloadedCycle.getBeneficiaryId()).isEqualTo(f.participantIdOf(leaver));
    }

    // repay

    @Test
    void aRepaymentPostsTwoEntriesSummingToZero() throws Exception {
        var f = setUp();
        var debtor = collectorWithDebt(f);

        var repayment = repay(debtor, f.participantIdOf(debtor), AMOUNT, newKey());

        var entries = ledgerEntryRepository.findByTransactionId(repayment.ledgerTransactionId());
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().mapToLong(LedgerEntry::getAmountKobo).sum()).isZero();
    }

    @Test
    void aPartialRepaymentReducesExposureAndLeavesTheExitOpen() throws Exception {
        var f = setUp();
        var debtor = collectorWithDebt(f);
        long debt = FULL_POT - AMOUNT;

        var exit = requestExit(debtor, f.roundId());
        repay(debtor, f.participantIdOf(debtor), AMOUNT, newKey());

        assertThat(exposureOf(f.admin(), f.participantIdOf(debtor)).exposureKobo())
                .isEqualTo(debt - AMOUNT);
        assertThat(exitStatus(exit.id())).isEqualTo(ExitStatus.PENDING_SETTLEMENT);
        assertThat(participant(f, debtor).getStatus()).isEqualTo(ParticipantStatus.PENDING_EXIT);
    }

    @Test
    void clearingTheDebtCompletesTheExit() throws Exception {
        var f = setUp();
        var debtor = collectorWithDebt(f);
        long debt = FULL_POT - AMOUNT;

        var exit = requestExit(debtor, f.roundId());
        repay(debtor, f.participantIdOf(debtor), debt, newKey());

        assertThat(exitStatus(exit.id())).isEqualTo(ExitStatus.COMPLETED);
        assertThat(participant(f, debtor).getStatus()).isEqualTo(ParticipantStatus.EXITED);
        assertThat(exposureOf(f.admin(), f.participantIdOf(debtor)).exposureKobo()).isZero();
    }

    @Test
    void repayingWithoutAnOpenExitIsAllowedAndSettlesNothingElse() throws Exception {
        var f = setUp();
        var debtor = collectorWithDebt(f);
        long debt = FULL_POT - AMOUNT;

        repay(debtor, f.participantIdOf(debtor), debt, newKey());

        assertThat(exposureOf(f.admin(), f.participantIdOf(debtor)).exposureKobo()).isZero();
        assertThat(participant(f, debtor).getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
    }

    @Test
    void repayingMoreThanIsOwedIsRejected() throws Exception {
        var f = setUp();
        var debtor = collectorWithDebt(f);

        repayAndExpect(debtor, f.participantIdOf(debtor), FULL_POT, newKey(), 400);
    }

    @Test
    void repayingWithNothingOwedIsRejected() throws Exception {
        var f = setUp();
        var member = f.members().getFirst();               // exposure zero

        repayAndExpect(member, f.participantIdOf(member), AMOUNT, newKey(), 409);
    }

    @Test
    void theSameIdempotencyKeyCreatesOneRepaymentAndOnePosting() throws Exception {
        var f = setUp();
        var debtor = collectorWithDebt(f);
        UUID participantId = f.participantIdOf(debtor);
        var key = newKey();

        var first = repay(debtor, participantId, AMOUNT, key);
        var second = repay(debtor, participantId, AMOUNT, key);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.ledgerTransactionId()).isEqualTo(first.ledgerTransactionId());
        assertThat(repaymentRepository.findByParticipantId(participantId)).hasSize(1);
        assertThat(ledgerEntryRepository.findByTransactionId(first.ledgerTransactionId())).hasSize(2);
    }

    @Test
    void aNonAdminCannotRepaySomeoneElsesDebt() throws Exception {
        var f = setUp();
        var debtor = collectorWithDebt(f);
        var other = f.membersOtherThan(debtor).stream()
                .filter(m -> !m.id().equals(f.admin().id()))
                .findFirst()
                .orElseThrow();

        repayAndExpect(other, f.participantIdOf(debtor), AMOUNT, newKey(), 403);
    }

    @Test
    void anAdminCanRecordACashRepaymentForAMember() throws Exception {
        var f = setUp();
        var debtor = collectorWithDebt(f);

        var repayment = repay(f.admin(), f.participantIdOf(debtor), AMOUNT,
                RepaymentMethod.CASH, newKey());

        assertThat(repayment.recordedBy()).isEqualTo(f.admin().id());
        assertThat(repayment.participant().id()).isEqualTo(debtor.id());
        assertThat(repayment.method()).isEqualTo(RepaymentMethod.CASH);
    }

    // Interactions with payouts, swaps and contributions

    @Test
    void aBeneficiaryWhoIsLeavingCannotBePaidOut() throws Exception {
        var f = setUp();
        var cycle = cycles(f).getFirst();
        var leaver = f.beneficiaryOf(cycle);

        client.contribute(leaver, cycle.getId(), AMOUNT, leaver.id(), newKey());
        requestExit(leaver, f.roundId());                  // PENDING_EXIT, cycle still theirs

        for (TestUser member : f.membersOtherThan(leaver)) {
            client.contribute(member, cycle.getId(), AMOUNT, member.id(), newKey());
        }

        payoutAndExpect(f.admin(), cycle.getId(), leaver.id(), 409);
    }

    @Test
    void requestingAnExitSupersedesPendingSwapsForThatMember() throws Exception {
        var f = setUp();
        var leaver = f.members().getFirst();               // zero exposure: exit completes at once
        var other = f.membersOtherThan(leaver).getFirst();

        var swap = client.requestSwap(leaver, f.roundId(), other.id());

        requestExit(leaver, f.roundId());

        assertThat(swapRequestRepository.findById(swap.id()).orElseThrow().getStatus())
                .isEqualTo(SwapStatus.SUPERSEDED);
    }

    @Test
    void aMemberAwaitingSettlementCanStillContribute() throws Exception {
        var f = setUp();
        var first = cycles(f).getFirst();
        var leaver = f.beneficiaryOf(first);

        client.contribute(leaver, first.getId(), AMOUNT, leaver.id(), newKey());
        requestExit(leaver, f.roundId());                  // PENDING_EXIT

        var second = cycles(f).get(1);
        client.contribute(leaver, second.getId(), AMOUNT, leaver.id(), newKey());
    }

    @Test
    void aVacantCycleStillCollectsContributionsButCannotBePaidOut() throws Exception {
        var f = setUp();
        var leaver = f.members().getFirst();
        UUID vacatedCycleId = cycleOf(f, leaver).getId();

        requestExit(leaver, f.roundId());                  // zero exposure — cycle becomes VACANT

        // The pot still fills, which is what funds settlement later.
        for (TestUser member : f.membersOtherThan(leaver)) {
            client.contribute(member, vacatedCycleId, AMOUNT, member.id(), newKey());
        }

        // But nobody can collect it.
        payoutAndExpect(f.admin(), vacatedCycleId, leaver.id(), 409);
    }

    // Structure survives an exit

    @Test
    void positionsRemainDistinctAfterSomeoneLeaves() throws Exception {
        var f = setUp();
        var leaver = f.members().getFirst();

        requestExit(leaver, f.roundId());

        var participants = roundParticipantRepository.findByRoundId(f.roundId());
        var positions = participants.stream()
                .map(RoundParticipant::getPayoutPosition)
                .sorted()
                .toList();

        // The leaver keeps their position — the round's schedule does not renumber.
        assertThat(positions).containsExactlyElementsOf(
                IntStream.rangeClosed(1, participants.size()).boxed().toList());

        assertThat(participants.stream().filter(p -> p.getStatus() == ParticipantStatus.ACTIVE))
                .hasSize(2);
        assertThat(cycles(f).stream().filter(c -> c.getStatus() == CycleStatus.VACANT))
                .hasSize(1);
    }

    // Domain helpers

    /** Returns the member who collected cycle 1's full pot having contributed once — exposure FULL_POT - AMOUNT. */
    private TestUser collectorWithDebt(Fixture f) throws Exception {
        var cycle = cycles(f).getFirst();
        var beneficiary = f.beneficiaryOf(cycle);

        for (TestUser member : f.members()) {
            client.contribute(member, cycle.getId(), AMOUNT, member.id(), newKey());
        }
        client.payout(f.admin(), cycle.getId(), beneficiary.id(), newKey());

        return beneficiary;
    }

    private List<Cycle> cycles(Fixture f) {
        return cycleRepository.findByRoundIdOrderByCycleNumberAsc(f.roundId());
    }

    private Cycle cycleOf(Fixture f, TestUser user) {
        UUID participantId = f.participantIdOf(user);
        return cycles(f).stream()
                .filter(c -> participantId.equals(c.getBeneficiaryId()))
                .findFirst()
                .orElseThrow();
    }

    private RoundParticipant participant(Fixture f, TestUser user) {
        return roundParticipantRepository.findById(f.participantIdOf(user)).orElseThrow();
    }

    private ExitStatus exitStatus(UUID exitId) {
        return exitRequestRepository.findById(exitId).orElseThrow().getStatus();
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

    private void requestExitAndExpect(TestUser caller, UUID roundId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/rounds/" + roundId + "/exit")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    private void cancelExit(TestUser caller, UUID exitId) throws Exception {
        mockMvc.perform(post("/exits/" + exitId + "/cancel")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk());
    }

    private RepaymentSummary repay(TestUser caller, UUID participantId, long amountKobo, String key) throws Exception {
        return repay(caller, participantId, amountKobo, RepaymentMethod.ONLINE, key);
    }

    private RepaymentSummary repay(TestUser caller, UUID participantId, long amountKobo,
                                   RepaymentMethod method, String key) throws Exception {
        var result = mockMvc.perform(post("/participants/" + participantId + "/repayments")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RepayRequest(amountKobo, method))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), RepaymentSummary.class);
    }

    private void repayAndExpect(TestUser caller, UUID participantId, long amountKobo,
                                String key, int expectedStatus) throws Exception {
        mockMvc.perform(post("/participants/" + participantId + "/repayments")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RepayRequest(amountKobo, RepaymentMethod.ONLINE))))
                .andExpect(status().is(expectedStatus));
    }

    private void payoutAndExpect(TestUser caller, UUID cycleId,
                                 UUID expectedBeneficiaryUserId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/cycles/" + cycleId + "/payout")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PayoutRequest(PayoutMethod.ONLINE, expectedBeneficiaryUserId))))
                .andExpect(status().is(expectedStatus));
    }

    private ExposureSummary exposureOf(TestUser caller, UUID participantId) throws Exception {
        var result = mockMvc.perform(get("/participants/" + participantId + "/exposure")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ExposureSummary.class);
    }

    // Setup — three members, one round, activated

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