package com.theninjadev.ajoapi.swap;

import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.round.Cycle;
import com.theninjadev.ajoapi.round.CycleRepository;
import com.theninjadev.ajoapi.round.ParticipantSummary;
import com.theninjadev.ajoapi.round.RoundDetail;
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

@SpringBootTest
@AutoConfigureMockMvc
class SwapTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CycleRepository cycleRepository;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper, userRepository);
    }

    private record Fixture(UUID groupId, UUID roundId,
                            TestUser admin, TestUser ada, TestUser eze,
                            List<TestUser> members,
                            List<Cycle> cycles,
                            Map<UUID, TestUser> userByParticipantId) {

        TestUser beneficiaryOf(Cycle cycle) {
            return userByParticipantId.get(cycle.getBeneficiaryId());
        }
    }

    // Requesting

    @Test
    void swappingWithYourselfIsRejected() throws Exception {
        var f = setUp(PAST_START);
        client.requestSwapAndExpect(f.admin(), f.roundId(), f.admin().id(), 400);
    }

    @Test
    void twoNewcomersInAGroupsFirstRoundCanRequestASwap() throws Exception {
        var f = setUp(PAST_START);
        client.requestSwap(f.admin(), f.roundId(), f.ada().id());
    }

    @Test
    void aSecondOutgoingPendingRequestIsRejected() throws Exception {
        var f = setUp(PAST_START);
        client.requestSwap(f.admin(), f.roundId(), f.ada().id());
        client.requestSwapAndExpect(f.admin(), f.roundId(), f.eze().id(), 409);
    }

    @Test
    void cancellingFreesTheRequesterToRequestAgain() throws Exception {
        var f = setUp(PAST_START);
        var swap = client.requestSwap(f.admin(), f.roundId(), f.ada().id());

        client.cancel(f.admin(), swap.id());

        client.requestSwap(f.admin(), f.roundId(), f.eze().id());
    }

    @Test
    void requestingASwapAfterYourOwnCycleIsPaidIsRejected() throws Exception {
        var f = setUp(PAST_START);
        var paidCycle = f.cycles().getFirst();
        var beneficiary = f.beneficiaryOf(paidCycle);
        var other = f.members().stream().filter(m -> !m.id().equals(beneficiary.id())).findFirst().orElseThrow();

        contributeAll(f, paidCycle);
        client.payout(f.admin(), paidCycle.getId(), beneficiary.id(), newKey());

        client.requestSwapAndExpectDetail(beneficiary, f.roundId(), other.id(), 409,
                "You have already collected your payout in this round and can no longer swap positions");
    }

    @Test
    void requestingASwapWithAMemberWhoseCycleIsPaidIsRejected() throws Exception {
        var f = setUp(PAST_START);
        var paidCycle = f.cycles().getFirst();
        var beneficiary = f.beneficiaryOf(paidCycle);
        var other = f.members().stream().filter(m -> !m.id().equals(beneficiary.id())).findFirst().orElseThrow();

        contributeAll(f, paidCycle);
        client.payout(f.admin(), paidCycle.getId(), beneficiary.id(), newKey());

        client.requestSwapAndExpectDetail(other, f.roundId(), beneficiary.id(), 409,
                "This member has already collected their payout in this round and can no longer swap positions");
    }

    // Decline / cancel

    @Test
    void onlyTheTargetCanDecline() throws Exception {
        var f = setUp(PAST_START);
        var swap = client.requestSwap(f.admin(), f.roundId(), f.ada().id());

        client.declineAndExpect(f.eze(), swap.id(), 403);
    }

    @Test
    void onlyTheRequesterCanCancel() throws Exception {
        var f = setUp(PAST_START);
        var swap = client.requestSwap(f.admin(), f.roundId(), f.ada().id());

        client.cancelAndExpect(f.eze(), swap.id(), 403);
    }

    @Test
    void aDeclinedRequestCannotBeCancelled() throws Exception {
        var f = setUp(PAST_START);
        var swap = client.requestSwap(f.admin(), f.roundId(), f.ada().id());

        client.decline(f.ada(), swap.id());

        client.cancelAndExpect(f.admin(), swap.id(), 409);
    }

    // Lists

    @Test
    void incomingAndOutgoingListsContainOnlyPendingWhileTheRoundListKeepsHistory() throws Exception {
        var f = setUp(PAST_START);

        var pending = client.requestSwap(f.admin(), f.roundId(), f.ada().id());

        var toCancel = client.requestSwap(f.ada(), f.roundId(), f.eze().id());
        client.cancel(f.ada(), toCancel.id());

        var toDecline = client.requestSwap(f.eze(), f.roundId(), f.admin().id());
        client.decline(f.admin(), toDecline.id());

        var roundHistory = client.listSwapsForRound(f.admin(), f.roundId());
        assertThat(roundHistory).extracting(SwapRequestSummary::id)
                .containsExactlyInAnyOrder(pending.id(), toCancel.id(), toDecline.id());
        assertThat(roundHistory).extracting(SwapRequestSummary::status)
                .containsExactlyInAnyOrder(SwapStatus.PENDING, SwapStatus.CANCELLED, SwapStatus.DECLINED);

        var adminOutgoing = client.listOutgoingSwaps(f.admin(), f.roundId());
        assertThat(adminOutgoing).extracting(SwapRequestSummary::id).containsExactly(pending.id());

        var adminIncoming = client.listIncomingSwaps(f.admin(), f.roundId());
        assertThat(adminIncoming).isEmpty();

        var adaIncoming = client.listIncomingSwaps(f.ada(), f.roundId());
        assertThat(adaIncoming).extracting(SwapRequestSummary::id).containsExactly(pending.id());

        var adaOutgoing = client.listOutgoingSwaps(f.ada(), f.roundId());
        assertThat(adaOutgoing).isEmpty();
    }

    // The veteran/newcomer rule

    @Test
    void newcomerInASecondRoundCannotMoveAheadOfAVeteran() throws Exception {
        var f = setUp(PAST_START);

        // Complete round 1 so admin/ada/eze become veterans of this group.
        for (Cycle cycle : f.cycles()) {
            contributeAll(f, cycle);
            client.payout(f.admin(), cycle.getId(), f.beneficiaryOf(cycle).id(), newKey());
        }

        var newcomer = client.registerUser("Newcomer");
        client.addToGroup(f.admin(), f.groupId(), newcomer);

        var round2Id = client.createRound(f.admin(), f.groupId(), AMOUNT, LocalDate.now().plusMonths(6).withDayOfMonth(28));
        client.addParticipant(f.admin(), round2Id, f.admin());
        client.addParticipant(f.admin(), round2Id, f.ada());
        client.addParticipant(f.admin(), round2Id, f.eze());
        client.addParticipant(f.admin(), round2Id, newcomer);

        client.activate(f.admin(), round2Id);

        // Activation always places every veteran ahead of every newcomer, so this
        // is rejected regardless of which veteran the newcomer targets.
        client.requestSwapAndExpectDetail(newcomer, round2Id, f.admin().id(), 409,
                "Members in their first round cannot move ahead of members who have completed a round");
    }

    // Round-level helpers

    private void contributeAll(Fixture f, Cycle cycle) throws Exception {
        for (TestUser member : f.members()) {
            client.contribute(member, cycle.getId(), AMOUNT, member.id(), newKey());
        }
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

        Map<UUID, TestUser> userByParticipantId = detail.participants().stream()
                .collect(Collectors.toMap(ParticipantSummary::id, p -> usersById.get(p.user().id())));

        var cycles = cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId);

        return new Fixture(groupId, roundId, admin, ada, eze, members, cycles, userByParticipantId);
    }
}
