package com.theninjadev.ajoapi.swap;

import com.theninjadev.ajoapi.contribution.Contribution;
import com.theninjadev.ajoapi.contribution.ContributionRepository;
import com.theninjadev.ajoapi.payout.Payout;
import com.theninjadev.ajoapi.payout.PayoutRepository;
import com.theninjadev.ajoapi.round.Cycle;
import com.theninjadev.ajoapi.round.CycleRepository;
import com.theninjadev.ajoapi.round.ParticipantSummary;
import com.theninjadev.ajoapi.round.RoundDetail;
import com.theninjadev.ajoapi.round.RoundParticipant;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class SwapAcceptTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;
    private static final int MEMBERS = 4;
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CycleRepository cycleRepository;
    @Autowired private RoundParticipantRepository roundParticipantRepository;
    @Autowired private PositionSwapRequestRepository swapRequestRepository;
    @Autowired private ContributionRepository contributionRepository;
    @Autowired private PayoutRepository payoutRepository;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper);
    }

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

        var swap = client.requestSwap(f.a(), f.roundId(), f.b().id());
        var accepted = client.accept(f.b(), swap.id());

        assertThat(accepted.status()).isEqualTo(SwapStatus.ACCEPTED);
        assertThat(positionOf(f, f.a())).isEqualTo(bBefore);
        assertThat(positionOf(f, f.b())).isEqualTo(aBefore);
    }

    @Test
    void acceptExchangesBothCyclesBeneficiaries() throws Exception {
        var f = setUp();
        UUID aCycleBefore = cycleOf(f, f.a()).getId();
        UUID bCycleBefore = cycleOf(f, f.b()).getId();

        var swap = client.requestSwap(f.a(), f.roundId(), f.b().id());
        client.accept(f.b(), swap.id());

        assertThat(cycleOf(f, f.a()).getId()).isEqualTo(bCycleBefore);
        assertThat(cycleOf(f, f.b()).getId()).isEqualTo(aCycleBefore);
    }

    @Test
    void structuralInvariantsStillHoldAfterASwap() throws Exception {
        var f = setUp();

        var swap = client.requestSwap(f.a(), f.roundId(), f.c().id());
        client.accept(f.c(), swap.id());

        assertStructuralInvariants(f.roundId());
    }

    @Test
    void membersNotInvolvedInTheSwapKeepTheirPositionAndCycle() throws Exception {
        var f = setUp();
        int dPosition = positionOf(f, f.d());
        UUID dCycle = cycleOf(f, f.d()).getId();

        var swap = client.requestSwap(f.a(), f.roundId(), f.b().id());
        client.accept(f.b(), swap.id());

        assertThat(positionOf(f, f.d())).isEqualTo(dPosition);
        assertThat(cycleOf(f, f.d()).getId()).isEqualTo(dCycle);
    }

    // ------------------------------------------------------------------
    // Authorization and state
    // ------------------------------------------------------------------

    @Test
    void theRequesterCannotAcceptTheirOwnRequest() throws Exception {
        var f = setUp();
        var swap = client.requestSwap(f.a(), f.roundId(), f.b().id());

        client.acceptAndExpect(f.a(), swap.id(), 403);
    }

    @Test
    void aThirdPartyCannotAcceptSomeoneElsesRequest() throws Exception {
        var f = setUp();
        var swap = client.requestSwap(f.a(), f.roundId(), f.b().id());

        client.acceptAndExpect(f.c(), swap.id(), 403);
        assertThat(swapStatus(swap.id())).isEqualTo(SwapStatus.PENDING);
    }

    @Test
    void aCancelledRequestCannotBeAccepted() throws Exception {
        var f = setUp();
        var swap = client.requestSwap(f.a(), f.roundId(), f.b().id());
        client.cancel(f.a(), swap.id());

        client.acceptAndExpect(f.b(), swap.id(), 409);
    }

    @Test
    void aDeclinedRequestCannotBeAccepted() throws Exception {
        var f = setUp();
        var swap = client.requestSwap(f.a(), f.roundId(), f.b().id());
        client.decline(f.b(), swap.id());

        client.acceptAndExpect(f.b(), swap.id(), 409);
    }

    @Test
    void aRequestCannotBeAcceptedTwice() throws Exception {
        var f = setUp();
        int aAfterFirstAccept;

        var swap = client.requestSwap(f.a(), f.roundId(), f.b().id());
        client.accept(f.b(), swap.id());
        aAfterFirstAccept = positionOf(f, f.a());

        client.acceptAndExpect(f.b(), swap.id(), 409);

        // The second attempt must not swap them back.
        assertThat(positionOf(f, f.a())).isEqualTo(aAfterFirstAccept);
    }

    // ------------------------------------------------------------------
    // Supersession
    // ------------------------------------------------------------------

    @Test
    void acceptingSupersedesOtherPendingRequestsInvolvingEitherParticipantOnly() throws Exception {
        var f = setUp();

        var accepted     = client.requestSwap(f.a(), f.roundId(), f.b().id());   // the one we accept
        var intoA        = client.requestSwap(f.c(), f.roundId(), f.a().id());   // involves A
        var fromB        = client.requestSwap(f.b(), f.roundId(), f.d().id());   // involves B
        var unrelated    = client.requestSwap(f.d(), f.roundId(), f.c().id());   // involves neither

        client.accept(f.b(), accepted.id());

        assertThat(swapStatus(accepted.id())).isEqualTo(SwapStatus.ACCEPTED);
        assertThat(swapStatus(intoA.id())).isEqualTo(SwapStatus.SUPERSEDED);
        assertThat(swapStatus(fromB.id())).isEqualTo(SwapStatus.SUPERSEDED);
        assertThat(swapStatus(unrelated.id())).isEqualTo(SwapStatus.PENDING);
    }

    @Test
    void aSupersededRequestCannotBeAccepted() throws Exception {
        var f = setUp();
        var first  = client.requestSwap(f.a(), f.roundId(), f.b().id());
        var second = client.requestSwap(f.c(), f.roundId(), f.a().id());

        client.accept(f.b(), first.id());                 // supersedes `second`

        client.acceptAndExpect(f.a(), second.id(), 409);
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

        var swap = client.requestSwap(last, f.roundId(), first.id());
        client.accept(first, swap.id());

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
        var aToB = client.requestSwap(f.a(), f.roundId(), f.b().id());   // B will accept
        var cToA = client.requestSwap(f.c(), f.roundId(), f.a().id());   // A will accept

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Callable<Integer> bAccepts = () -> {
            ready.countDown();
            go.await();
            return client.acceptStatus(f.b(), aToB.id());
        };
        Callable<Integer> aAccepts = () -> {
            ready.countDown();
            go.await();
            return client.acceptStatus(f.a(), cToA.id());
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
                client.contribute(member, cycle.getId(), AMOUNT, member.id(), UUID.randomUUID().toString());
            }
            var beneficiary = f.userByParticipantId().get(cycle.getBeneficiaryId());
            client.payout(f.admin(), cycle.getId(), beneficiary.id(), UUID.randomUUID().toString());
        }
    }

    // ------------------------------------------------------------------
    // Setup — four members, one round, activated
    // ------------------------------------------------------------------

    private Fixture setUp() throws Exception {
        var admin = client.registerUser("Alice");
        var bola = client.registerUser("Bola");
        var chidi = client.registerUser("Chidi");
        var dayo = client.registerUser("Dayo");
        var members = List.of(admin, bola, chidi, dayo);

        var groupId = client.createGroup(admin, "Alice's Ajo");
        for (TestUser member : members.subList(1, members.size())) {
            client.addToGroup(admin, groupId, member);
        }

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

        return new Fixture(roundId, members, participantIdByUserId, userByParticipantId);
    }
}
