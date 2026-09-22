package com.theninjadev.ajoapi.payout;

import com.theninjadev.ajoapi.auth.AuthResponse;
import com.theninjadev.ajoapi.auth.RegisterRequest;
import com.theninjadev.ajoapi.contribution.ContributeRequest;
import com.theninjadev.ajoapi.contribution.Contribution;
import com.theninjadev.ajoapi.contribution.ContributionRepository;
import com.theninjadev.ajoapi.group.CreateGroupRequest;
import com.theninjadev.ajoapi.group.GroupInviteSummary;
import com.theninjadev.ajoapi.group.GroupSummary;
import com.theninjadev.ajoapi.group.InviteMemberRequest;
import com.theninjadev.ajoapi.ledger.AccountType;
import com.theninjadev.ajoapi.ledger.LedgerAccountRepository;
import com.theninjadev.ajoapi.ledger.LedgerAccounts;
import com.theninjadev.ajoapi.ledger.LedgerEntry;
import com.theninjadev.ajoapi.ledger.LedgerEntryRepository;
import com.theninjadev.ajoapi.round.AddParticipantRequest;
import com.theninjadev.ajoapi.round.CreateRoundRequest;
import com.theninjadev.ajoapi.round.Cycle;
import com.theninjadev.ajoapi.round.CycleRepository;
import com.theninjadev.ajoapi.round.CycleStatus;
import com.theninjadev.ajoapi.round.ParticipantSummary;
import com.theninjadev.ajoapi.round.RoundDetail;
import com.theninjadev.ajoapi.round.RoundRepository;
import com.theninjadev.ajoapi.round.RoundStatus;
import com.theninjadev.ajoapi.round.RoundSummary;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PayoutTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;          // ₦10,000 per member per cycle
    private static final long FULL_POT = 3 * AMOUNT;         // three participants
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);

    // Globally unique phone numbers — the container is shared and nothing rolls back.
    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CycleRepository cycleRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private PayoutRepository payoutRepository;
    @Autowired private ShortfallClaimRepository shortfallClaimRepository;
    @Autowired private ContributionRepository contributionRepository;
    @Autowired private LedgerAccountRepository ledgerAccountRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;

    private record TestUser(UUID id, String phone, String accessToken) {}

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
    }

    // Structural

    @Test
    void fullPoolPaysTheFullExpectedAmount() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();
        var beneficiary = f.beneficiaryOf(cycle);

        contributeAll(f, cycle);
        var payout = payout(beneficiary, cycle.getId(), newKey());

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
        var payout = payout(f.admin(), cycle.getId(), newKey());

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

        payout(f.admin(), cycle.getId(), newKey());

        assertThat(poolBalance(f.roundId())).isZero();
    }

    @Test
    void payoutMarksTheCyclePaid() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();

        contributeAll(f, cycle);
        payout(f.admin(), cycle.getId(), newKey());

        var reloaded = cycleRepository.findById(cycle.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CycleStatus.PAID);
    }

    // The balance cap

    @Test
    void partialPoolPaysOnlyWhatWasCollected() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();

        // Two of three contribute.
        contribute(f.admin(), cycle.getId(), newKey());
        contribute(f.ada(), cycle.getId(), newKey());

        var payout = payout(f.admin(), cycle.getId(), newKey());

        assertThat(payout.expectedAmountKobo()).isEqualTo(FULL_POT);
        assertThat(payout.actualAmountKobo()).isEqualTo(2 * AMOUNT);
        assertThat(payout.shortfallKobo()).isEqualTo(AMOUNT);
        assertThat(poolBalance(f.roundId())).isZero();                // nothing left over, nothing overdrawn
    }

    @Test
    void partialPayoutCreatesAnOpenShortfallClaimForTheBeneficiary() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();

        contribute(f.admin(), cycle.getId(), newKey());
        contribute(f.ada(), cycle.getId(), newKey());
        payout(f.admin(), cycle.getId(), newKey());

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
        payout(f.admin(), cycle.getId(), newKey());

        assertThat(shortfallClaimRepository.findByCycleId(cycle.getId())).isEmpty();
    }

    @Test
    void emptyPoolIsRejectedAndWritesNothing() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();

        payoutAndExpect(f.admin(), cycle.getId(), newKey(), 409);

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
        payout(f.admin(), cycle.getId(), newKey());

        var round = roundRepository.findById(f.roundId()).orElseThrow();
        assertThat(round.getStatus()).isEqualTo(RoundStatus.ACTIVE);
    }

    @Test
    void contributingToACompletedRoundIsRejected() throws Exception {
        // The RoundNotActiveException case that was unreachable in slice 5.
        var f = setUp(PAST_START);
        runFullRound(f);

        contributeAndExpect(f.ada(), f.cycles().getFirst().getId(), newKey(), 409);
    }

    // Idempotency
    
    @Test
    void sameKeyTwiceCreatesOnePayoutAndOnePosting() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();
        var key = newKey();

        contributeAll(f, cycle);
        var first = payout(f.admin(), cycle.getId(), key);
        var second = payout(f.admin(), cycle.getId(), key);

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

        var retried = payout(f.admin(), lastCycle.getId(), lastPayout.idempotencyKey());

        assertThat(retried.id()).isEqualTo(lastPayout.summary().id());
    }

    @Test
    void reusingAKeyForADifferentCycleIsRejected() throws Exception {
        var f = setUp(PAST_START);
        var first = f.cycles().get(0);
        var second = f.cycles().get(1);
        var key = newKey();

        contributeAll(f, first);
        payout(f.admin(), first.getId(), key);

        payoutAndExpect(f.admin(), second.getId(), key, 409);
    }

    @Test
    void payingOutTheSameCycleTwiceIsRejected() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.cycles().getFirst();

        contributeAll(f, cycle);
        payout(f.admin(), cycle.getId(), newKey());

        payoutAndExpect(f.admin(), cycle.getId(), newKey(), 409);
    }

    // Authorization and timing

    @Test
    void nonBeneficiaryNonAdminCannotTriggerAPayout() throws Exception {
        var f = setUp(PAST_START);
        var cycle = f.firstCycleNotBelongingTo(f.ada());

        contributeAll(f, cycle);

        payoutAndExpect(f.ada(), cycle.getId(), newKey(), 403);
    }

    @Test
    void adminRecordingAPayoutAttributesItToTheBeneficiary() throws Exception {
        // Regression guard for the caller-versus-subject bug: the payout belongs
        // to the cycle's beneficiary, not to the admin who recorded it.
        var f = setUp(PAST_START);
        var cycle = f.firstCycleNotBelongingTo(f.admin());
        var beneficiary = f.beneficiaryOf(cycle);

        contributeAll(f, cycle);
        var payout = payout(f.admin(), cycle.getId(), newKey());

        assertThat(payout.recordedBy()).isEqualTo(f.admin().id());
        assertThat(payout.beneficiary().id()).isEqualTo(beneficiary.id());
        assertThat(payout.participantId()).isEqualTo(cycle.getBeneficiaryId());
    }

    @Test
    void payoutBeforeTheDueDateIsRejected() throws Exception {
        var f = setUp(LocalDate.now().plusMonths(6).withDayOfMonth(28));
        var cycle = f.cycles().getFirst();

        payoutAndExpect(f.admin(), cycle.getId(), newKey(), 409);
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
            payouts.add(new RecordedPayout(payout(f.admin(), cycle.getId(), key), key));
        }
        return payouts;
    }

    private void contributeAll(Fixture f, Cycle cycle) throws Exception {
        for (TestUser member : f.members()) {
            contribute(member, cycle.getId(), newKey());
        }
    }

    private long poolBalance(UUID roundId) {
        var pool = ledgerAccountRepository
                .findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, roundId)
                .orElseThrow();
        return ledgerEntryRepository.sumAmountKoboByAccountId(pool.getId());
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    // HTTP helpers — payouts and contributions
    
    private PayoutSummary payout(TestUser caller, UUID cycleId, String key) throws Exception {
        var result = mockMvc.perform(post("/cycles/" + cycleId + "/payout")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PayoutRequest(PayoutMethod.ONLINE))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), PayoutSummary.class);
    }

    private void payoutAndExpect(TestUser caller, UUID cycleId, String key, int expectedStatus) throws Exception {
        mockMvc.perform(post("/cycles/" + cycleId + "/payout")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PayoutRequest(PayoutMethod.ONLINE))))
                .andExpect(status().is(expectedStatus));
    }

    private void contribute(TestUser caller, UUID cycleId, String key) throws Exception {
        mockMvc.perform(post("/cycles/" + cycleId + "/contributions")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ContributeRequest(AMOUNT, caller.id(), null))))
                .andExpect(status().isCreated());
    }

    private void contributeAndExpect(TestUser caller, UUID cycleId, String key, int expectedStatus) throws Exception {
        mockMvc.perform(post("/cycles/" + cycleId + "/contributions")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ContributeRequest(AMOUNT, caller.id(), null))))
                .andExpect(status().is(expectedStatus));
    }

    // Setup — three members, one round, activated
    
    private Fixture setUp(LocalDate firstPayoutDate) throws Exception {
        var admin = registerUser("Alice");
        var ada = registerUser("Ada");
        var eze = registerUser("Eze");
        var members = List.of(admin, ada, eze);

        var groupId = createGroup(admin, "Alice's Ajo");
        addToGroup(admin, groupId, ada);
        addToGroup(admin, groupId, eze);

        var roundId = createRound(admin, groupId, AMOUNT, firstPayoutDate);
        for (TestUser member : members) {
            addParticipant(admin, roundId, member);
        }

        RoundDetail detail = activate(admin, roundId);

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

    private TestUser registerUser(String fullName) throws Exception {
        String phone = "0907%07d".formatted(PHONE_COUNTER.incrementAndGet());
        var request = new RegisterRequest(phone, "password123", fullName, null);
        var result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        AuthResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), AuthResponse.class);
        return new TestUser(response.user().id(), response.user().phone(), response.accessToken());
    }

    private UUID createGroup(TestUser owner, String name) throws Exception {
        var request = new CreateGroupRequest(name, "description");
        var result = mockMvc.perform(post("/groups")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), GroupSummary.class).id();
    }

    private void addToGroup(TestUser admin, UUID groupId, TestUser invitee) throws Exception {
        var request = new InviteMemberRequest(invitee.phone());
        var result = mockMvc.perform(post("/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        var invite = objectMapper.readValue(
                result.getResponse().getContentAsString(), GroupInviteSummary.class);

        mockMvc.perform(post("/groups/invites/" + invite.id() + "/accept")
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk());
    }

    private UUID createRound(TestUser admin, UUID groupId, long amountKobo, LocalDate firstPayoutDate) throws Exception {
        var request = new CreateRoundRequest(amountKobo, firstPayoutDate);
        var result = mockMvc.perform(post("/groups/" + groupId + "/rounds")
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), RoundSummary.class).id();
    }

    private void addParticipant(TestUser admin, UUID roundId, TestUser participant) throws Exception {
        mockMvc.perform(post("/rounds/" + roundId + "/participants")
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddParticipantRequest(participant.id()))))
                .andExpect(status().isCreated());
    }

    private RoundDetail activate(TestUser caller, UUID roundId) throws Exception {
        var result = mockMvc.perform(post("/rounds/" + roundId + "/activate")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), RoundDetail.class);
    }
}