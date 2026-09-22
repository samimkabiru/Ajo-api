package com.theninjadev.ajoapi.swap;

import com.theninjadev.ajoapi.auth.AuthResponse;
import com.theninjadev.ajoapi.auth.RegisterRequest;
import com.theninjadev.ajoapi.contribution.ContributeRequest;
import com.theninjadev.ajoapi.contribution.Contribution;
import com.theninjadev.ajoapi.contribution.ContributionRepository;
import com.theninjadev.ajoapi.group.CreateGroupRequest;
import com.theninjadev.ajoapi.group.GroupInviteSummary;
import com.theninjadev.ajoapi.group.GroupSummary;
import com.theninjadev.ajoapi.group.InviteMemberRequest;
import com.theninjadev.ajoapi.payout.Payout;
import com.theninjadev.ajoapi.payout.PayoutMethod;
import com.theninjadev.ajoapi.payout.PayoutRepository;
import com.theninjadev.ajoapi.payout.PayoutRequest;
import com.theninjadev.ajoapi.round.AddParticipantRequest;
import com.theninjadev.ajoapi.round.CreateRoundRequest;
import com.theninjadev.ajoapi.round.Cycle;
import com.theninjadev.ajoapi.round.CycleRepository;
import com.theninjadev.ajoapi.round.ParticipantSummary;
import com.theninjadev.ajoapi.round.RoundDetail;
import com.theninjadev.ajoapi.round.RoundParticipant;
import com.theninjadev.ajoapi.round.RoundParticipantRepository;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SwapAcceptTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;
    private static final int MEMBERS = 4;
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);

    // Globally unique phone numbers — the container is shared and nothing rolls back.
    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CycleRepository cycleRepository;
    @Autowired private RoundParticipantRepository roundParticipantRepository;
    @Autowired private PositionSwapRequestRepository swapRequestRepository;
    @Autowired private ContributionRepository contributionRepository;
    @Autowired private PayoutRepository payoutRepository;

    private record TestUser(UUID id, String phone, String accessToken) {}

    /** Four members in a group's first round — everyone is a newcomer, so every swap is permitted. */
    private record Fixture(UUID roundId,
                           List<TestUser> members,
                           Map<UUID, UUID> participantIdByUserId,
                           Map<UUID, TestUser> userByParticipantId) {

        TestUser admin() { return members.get(0); }
        TestUser a()     { return members.get(0); }
        TestUser b()     { return members.get(1); }
        TestUser c()     { return members.get(2); }
        TestUser d()     { return members.get(3); }

        UUID participantIdOf(TestUser user) {
            return participantIdByUserId.get(user.id());
        }
    }

    // ------------------------------------------------------------------
    // The exchange
    // ------------------------------------------------------------------

    @Test
    void acceptExchangesBothParticipantsPositions() throws Exception {
        var f = setUp();
        int aBefore = positionOf(f, f.a());
        int bBefore = positionOf(f, f.b());

        var swap = requestSwap(f.a(), f.roundId(), f.b());
        var accepted = accept(f.b(), swap.id());

        assertThat(accepted.status()).isEqualTo(SwapStatus.ACCEPTED);
        assertThat(positionOf(f, f.a())).isEqualTo(bBefore);
        assertThat(positionOf(f, f.b())).isEqualTo(aBefore);
    }

    @Test
    void acceptExchangesBothCyclesBeneficiaries() throws Exception {
        var f = setUp();
        UUID aCycleBefore = cycleOf(f, f.a()).getId();
        UUID bCycleBefore = cycleOf(f, f.b()).getId();

        var swap = requestSwap(f.a(), f.roundId(), f.b());
        accept(f.b(), swap.id());

        assertThat(cycleOf(f, f.a()).getId()).isEqualTo(bCycleBefore);
        assertThat(cycleOf(f, f.b()).getId()).isEqualTo(aCycleBefore);
    }

    @Test
    void structuralInvariantsStillHoldAfterASwap() throws Exception {
        var f = setUp();

        var swap = requestSwap(f.a(), f.roundId(), f.c());
        accept(f.c(), swap.id());

        assertStructuralInvariants(f.roundId());
    }

    @Test
    void membersNotInvolvedInTheSwapKeepTheirPositionAndCycle() throws Exception {
        var f = setUp();
        int dPosition = positionOf(f, f.d());
        UUID dCycle = cycleOf(f, f.d()).getId();

        var swap = requestSwap(f.a(), f.roundId(), f.b());
        accept(f.b(), swap.id());

        assertThat(positionOf(f, f.d())).isEqualTo(dPosition);
        assertThat(cycleOf(f, f.d()).getId()).isEqualTo(dCycle);
    }

    // ------------------------------------------------------------------
    // Authorization and state
    // ------------------------------------------------------------------

    @Test
    void theRequesterCannotAcceptTheirOwnRequest() throws Exception {
        var f = setUp();
        var swap = requestSwap(f.a(), f.roundId(), f.b());

        acceptAndExpect(f.a(), swap.id(), 403);
    }

    @Test
    void aThirdPartyCannotAcceptSomeoneElsesRequest() throws Exception {
        var f = setUp();
        var swap = requestSwap(f.a(), f.roundId(), f.b());

        acceptAndExpect(f.c(), swap.id(), 403);
        assertThat(swapStatus(swap.id())).isEqualTo(SwapStatus.PENDING);
    }

    @Test
    void aCancelledRequestCannotBeAccepted() throws Exception {
        var f = setUp();
        var swap = requestSwap(f.a(), f.roundId(), f.b());
        cancel(f.a(), swap.id());

        acceptAndExpect(f.b(), swap.id(), 409);
    }

    @Test
    void aDeclinedRequestCannotBeAccepted() throws Exception {
        var f = setUp();
        var swap = requestSwap(f.a(), f.roundId(), f.b());
        decline(f.b(), swap.id());

        acceptAndExpect(f.b(), swap.id(), 409);
    }

    @Test
    void aRequestCannotBeAcceptedTwice() throws Exception {
        var f = setUp();
        int aAfterFirstAccept;

        var swap = requestSwap(f.a(), f.roundId(), f.b());
        accept(f.b(), swap.id());
        aAfterFirstAccept = positionOf(f, f.a());

        acceptAndExpect(f.b(), swap.id(), 409);

        // The second attempt must not swap them back.
        assertThat(positionOf(f, f.a())).isEqualTo(aAfterFirstAccept);
    }

    // ------------------------------------------------------------------
    // Supersession
    // ------------------------------------------------------------------

    @Test
    void acceptingSupersedesOtherPendingRequestsInvolvingEitherParticipantOnly() throws Exception {
        var f = setUp();

        var accepted     = requestSwap(f.a(), f.roundId(), f.b());   // the one we accept
        var intoA        = requestSwap(f.c(), f.roundId(), f.a());   // involves A
        var fromB        = requestSwap(f.b(), f.roundId(), f.d());   // involves B
        var unrelated    = requestSwap(f.d(), f.roundId(), f.c());   // involves neither

        accept(f.b(), accepted.id());

        assertThat(swapStatus(accepted.id())).isEqualTo(SwapStatus.ACCEPTED);
        assertThat(swapStatus(intoA.id())).isEqualTo(SwapStatus.SUPERSEDED);
        assertThat(swapStatus(fromB.id())).isEqualTo(SwapStatus.SUPERSEDED);
        assertThat(swapStatus(unrelated.id())).isEqualTo(SwapStatus.PENDING);
    }

    @Test
    void aSupersededRequestCannotBeAccepted() throws Exception {
        var f = setUp();
        var first  = requestSwap(f.a(), f.roundId(), f.b());
        var second = requestSwap(f.c(), f.roundId(), f.a());

        accept(f.b(), first.id());                 // supersedes `second`

        acceptAndExpect(f.a(), second.id(), 409);
    }

    // ------------------------------------------------------------------
    // End to end: the swap actually changes who collects, and the round still balances
    // ------------------------------------------------------------------

    @Test
    void afterAnEmergencySwapTheRequesterCollectsEarlyAndTheRoundStillNetsToZero() throws Exception {
        var f = setUp();

        // The member due last asks the member due first to trade — the emergency case.
        var byPosition = f.members().stream()
                .sorted(Comparator.comparingInt(m -> positionOf(f, m)))
                .toList();
        var first = byPosition.getFirst();
        var last = byPosition.getLast();

        var swap = requestSwap(last, f.roundId(), first);
        accept(first, swap.id());

        runFullRound(f);

        // The member who asked now collected in cycle 1.
        var cycleOne = cycleRepository.findByRoundIdOrderByCycleNumberAsc(f.roundId()).getFirst();
        var cycleOnePayout = payoutRepository.findByCycleId(cycleOne.getId()).orElseThrow();
        assertThat(cycleOnePayout.getParticipantId()).isEqualTo(f.participantIdOf(last));

        // And everyone still contributed exactly what they collected, collecting exactly once.
        for (TestUser member : f.members()) {
            UUID participantId = f.participantIdOf(member);

            long contributed = contributionRepository.findByParticipantId(participantId).stream()
                    .mapToLong(Contribution::getAmountKobo)
                    .sum();
            var payouts = payoutRepository.findByParticipantId(participantId);
            long collected = payouts.stream().mapToLong(Payout::getActualAmountKobo).sum();

            assertThat(payouts).as("payouts for %s", member.id()).hasSize(1);
            assertThat(contributed).as("contributed by %s", member.id()).isEqualTo(MEMBERS * AMOUNT);
            assertThat(collected).as("collected by %s", member.id()).isEqualTo(contributed);
        }
    }

    // ------------------------------------------------------------------
    // Concurrency — the test that proves the locking works
    // ------------------------------------------------------------------

    @Test
    void twoConflictingAcceptsAtTheSameMomentResolveToExactlyOneSwap() throws Exception {
        var f = setUp();

        // Both requests involve A. Accepting either one invalidates the other.
        var aToB = requestSwap(f.a(), f.roundId(), f.b());   // B will accept
        var cToA = requestSwap(f.c(), f.roundId(), f.a());   // A will accept

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Callable<Integer> bAccepts = () -> {
            ready.countDown();
            go.await();
            return acceptStatus(f.b(), aToB.id());
        };
        Callable<Integer> aAccepts = () -> {
            ready.countDown();
            go.await();
            return acceptStatus(f.a(), cToA.id());
        };

        try {
            var first = pool.submit(bAccepts);
            var second = pool.submit(aAccepts);

            ready.await(10, TimeUnit.SECONDS);   // both threads parked at the gate
            go.countDown();                      // release them together

            int s1 = first.get(30, TimeUnit.SECONDS);
            int s2 = second.get(30, TimeUnit.SECONDS);

            // One wins, one is turned away cleanly — never two successes, never a 500.
            assertThat(List.of(s1, s2)).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        // Exactly one request went through; the other was superseded, not left dangling.
        assertThat(List.of(swapStatus(aToB.id()), swapStatus(cToA.id())))
                .containsExactlyInAnyOrder(SwapStatus.ACCEPTED, SwapStatus.SUPERSEDED);

        // And the round is still structurally sound.
        assertStructuralInvariants(f.roundId());
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    /** Positions are exactly 1..N, every participant owns exactly one cycle, and cycle k belongs to position k. */
    private void assertStructuralInvariants(UUID roundId) {
        var participants = roundParticipantRepository.findByRoundId(roundId);
        var cycles = cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId);

        var positions = participants.stream()
                .map(RoundParticipant::getPayoutPosition)
                .sorted()
                .toList();
        assertThat(positions).containsExactlyElementsOf(
                IntStream.rangeClosed(1, participants.size()).boxed().toList());

        var beneficiaries = cycles.stream().map(Cycle::getBeneficiaryId).toList();
        assertThat(beneficiaries).doesNotHaveDuplicates();
        assertThat(beneficiaries).containsExactlyInAnyOrderElementsOf(
                participants.stream().map(RoundParticipant::getId).toList());

        Map<Integer, UUID> participantAtPosition = participants.stream()
                .collect(Collectors.toMap(RoundParticipant::getPayoutPosition, RoundParticipant::getId));
        for (Cycle cycle : cycles) {
            assertThat(cycle.getBeneficiaryId())
                    .as("beneficiary of cycle %d", cycle.getCycleNumber())
                    .isEqualTo(participantAtPosition.get(cycle.getCycleNumber()));
        }
    }

    // ------------------------------------------------------------------
    // Fresh reads — never trust a value captured before a swap
    // ------------------------------------------------------------------

    private int positionOf(Fixture f, TestUser user) {
        return roundParticipantRepository.findById(f.participantIdOf(user)).orElseThrow().getPayoutPosition();
    }

    private Cycle cycleOf(Fixture f, TestUser user) {
        UUID participantId = f.participantIdOf(user);
        return cycleRepository.findByRoundIdOrderByCycleNumberAsc(f.roundId()).stream()
                .filter(c -> participantId.equals(c.getBeneficiaryId()))
                .findFirst()
                .orElseThrow();
    }

    private SwapStatus swapStatus(UUID swapId) {
        return swapRequestRepository.findById(swapId).orElseThrow().getStatus();
    }

    /** Re-reads cycles on every call, so it pays whoever owns each cycle *now*, after any swap. */
    private void runFullRound(Fixture f) throws Exception {
        for (Cycle cycle : cycleRepository.findByRoundIdOrderByCycleNumberAsc(f.roundId())) {
            for (TestUser member : f.members()) {
                contribute(member, cycle.getId());
            }
            var beneficiary = f.userByParticipantId().get(cycle.getBeneficiaryId());
            payout(f.admin(), cycle.getId(), beneficiary.id());
        }
    }

    // ------------------------------------------------------------------
    // HTTP helpers — swaps
    // ------------------------------------------------------------------

    private SwapRequestSummary requestSwap(TestUser requester, UUID roundId, TestUser target) throws Exception {
        var result = mockMvc.perform(post("/rounds/" + roundId + "/swaps")
                        .header("Authorization", "Bearer " + requester.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSwapRequest(target.id()))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), SwapRequestSummary.class);
    }

    private SwapRequestSummary accept(TestUser caller, UUID swapId) throws Exception {
        var result = mockMvc.perform(post("/swaps/" + swapId + "/accept")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), SwapRequestSummary.class);
    }

    private void acceptAndExpect(TestUser caller, UUID swapId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/swaps/" + swapId + "/accept")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    /** Returns the raw status instead of asserting — used from worker threads in the race test. */
    private int acceptStatus(TestUser caller, UUID swapId) throws Exception {
        return mockMvc.perform(post("/swaps/" + swapId + "/accept")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private void cancel(TestUser caller, UUID swapId) throws Exception {
        mockMvc.perform(post("/swaps/" + swapId + "/cancel")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk());
    }

    private void decline(TestUser caller, UUID swapId) throws Exception {
        mockMvc.perform(post("/swaps/" + swapId + "/decline")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // HTTP helpers — contributions and payouts
    // ------------------------------------------------------------------

    private void contribute(TestUser caller, UUID cycleId) throws Exception {
        mockMvc.perform(post("/cycles/" + cycleId + "/contributions")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ContributeRequest(AMOUNT, caller.id(), null))))
                .andExpect(status().isCreated());
    }

    private void payout(TestUser caller, UUID cycleId, UUID expectedBeneficiaryUserId) throws Exception {
        mockMvc.perform(post("/cycles/" + cycleId + "/payout")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PayoutRequest(PayoutMethod.ONLINE, expectedBeneficiaryUserId))))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------
    // Setup — four members, one round, activated
    // ------------------------------------------------------------------

    private Fixture setUp() throws Exception {
        var admin = registerUser("Alice");
        var bola = registerUser("Bola");
        var chidi = registerUser("Chidi");
        var dayo = registerUser("Dayo");
        var members = List.of(admin, bola, chidi, dayo);

        var groupId = createGroup(admin, "Alice's Ajo");
        for (TestUser member : members.subList(1, members.size())) {
            addToGroup(admin, groupId, member);
        }

        var roundId = createRound(admin, groupId, AMOUNT, PAST_START);
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

        return new Fixture(roundId, members, participantIdByUserId, userByParticipantId);
    }

    private TestUser registerUser(String fullName) throws Exception {
        String phone = "0905%07d".formatted(PHONE_COUNTER.incrementAndGet());
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
