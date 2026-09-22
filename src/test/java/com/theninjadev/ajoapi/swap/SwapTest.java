package com.theninjadev.ajoapi.swap;

import com.theninjadev.ajoapi.auth.AuthResponse;
import com.theninjadev.ajoapi.auth.RegisterRequest;
import com.theninjadev.ajoapi.contribution.ContributeRequest;
import com.theninjadev.ajoapi.group.CreateGroupRequest;
import com.theninjadev.ajoapi.group.GroupInviteSummary;
import com.theninjadev.ajoapi.group.GroupSummary;
import com.theninjadev.ajoapi.group.InviteMemberRequest;
import com.theninjadev.ajoapi.payout.PayoutMethod;
import com.theninjadev.ajoapi.payout.PayoutRequest;
import com.theninjadev.ajoapi.round.AddParticipantRequest;
import com.theninjadev.ajoapi.round.CreateRoundRequest;
import com.theninjadev.ajoapi.round.Cycle;
import com.theninjadev.ajoapi.round.CycleRepository;
import com.theninjadev.ajoapi.round.ParticipantSummary;
import com.theninjadev.ajoapi.round.RoundDetail;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SwapTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);

    // Globally unique phone numbers — the container is shared and nothing rolls back.
    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CycleRepository cycleRepository;

    private record TestUser(UUID id, String phone, String accessToken) {}

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
        requestSwapAndExpect(f.admin(), f.roundId(), f.admin().id(), 400);
    }

    @Test
    void twoNewcomersInAGroupsFirstRoundCanRequestASwap() throws Exception {
        var f = setUp(PAST_START);
        requestSwap(f.admin(), f.roundId(), f.ada().id());
    }

    @Test
    void aSecondOutgoingPendingRequestIsRejected() throws Exception {
        var f = setUp(PAST_START);
        requestSwap(f.admin(), f.roundId(), f.ada().id());
        requestSwapAndExpect(f.admin(), f.roundId(), f.eze().id(), 409);
    }

    @Test
    void cancellingFreesTheRequesterToRequestAgain() throws Exception {
        var f = setUp(PAST_START);
        var swap = requestSwap(f.admin(), f.roundId(), f.ada().id());

        cancel(f.admin(), swap.id());

        requestSwap(f.admin(), f.roundId(), f.eze().id());
    }

    @Test
    void requestingASwapAfterYourOwnCycleIsPaidIsRejected() throws Exception {
        var f = setUp(PAST_START);
        var paidCycle = f.cycles().getFirst();
        var beneficiary = f.beneficiaryOf(paidCycle);
        var other = f.members().stream().filter(m -> !m.id().equals(beneficiary.id())).findFirst().orElseThrow();

        contributeAll(f, paidCycle);
        payout(f.admin(), paidCycle.getId(), beneficiary.id(), newKey());

        requestSwapAndExpectDetail(beneficiary, f.roundId(), other.id(), 409,
                "You have already collected your payout in this round and can no longer swap positions");
    }

    @Test
    void requestingASwapWithAMemberWhoseCycleIsPaidIsRejected() throws Exception {
        var f = setUp(PAST_START);
        var paidCycle = f.cycles().getFirst();
        var beneficiary = f.beneficiaryOf(paidCycle);
        var other = f.members().stream().filter(m -> !m.id().equals(beneficiary.id())).findFirst().orElseThrow();

        contributeAll(f, paidCycle);
        payout(f.admin(), paidCycle.getId(), beneficiary.id(), newKey());

        requestSwapAndExpectDetail(other, f.roundId(), beneficiary.id(), 409,
                "This member has already collected their payout in this round and can no longer swap positions");
    }

    // Decline / cancel

    @Test
    void onlyTheTargetCanDecline() throws Exception {
        var f = setUp(PAST_START);
        var swap = requestSwap(f.admin(), f.roundId(), f.ada().id());

        declineAndExpect(f.eze(), swap.id(), 403);
    }

    @Test
    void onlyTheRequesterCanCancel() throws Exception {
        var f = setUp(PAST_START);
        var swap = requestSwap(f.admin(), f.roundId(), f.ada().id());

        cancelAndExpect(f.eze(), swap.id(), 403);
    }

    @Test
    void aDeclinedRequestCannotBeCancelled() throws Exception {
        var f = setUp(PAST_START);
        var swap = requestSwap(f.admin(), f.roundId(), f.ada().id());

        decline(f.ada(), swap.id());

        cancelAndExpect(f.admin(), swap.id(), 409);
    }

    // Lists

    @Test
    void incomingAndOutgoingListsContainOnlyPendingWhileTheRoundListKeepsHistory() throws Exception {
        var f = setUp(PAST_START);

        var pending = requestSwap(f.admin(), f.roundId(), f.ada().id());

        var toCancel = requestSwap(f.ada(), f.roundId(), f.eze().id());
        cancel(f.ada(), toCancel.id());

        var toDecline = requestSwap(f.eze(), f.roundId(), f.admin().id());
        decline(f.admin(), toDecline.id());

        var roundHistory = listForRound(f.admin(), f.roundId());
        assertThat(roundHistory).extracting(SwapRequestSummary::id)
                .containsExactlyInAnyOrder(pending.id(), toCancel.id(), toDecline.id());
        assertThat(roundHistory).extracting(SwapRequestSummary::status)
                .containsExactlyInAnyOrder(SwapStatus.PENDING, SwapStatus.CANCELLED, SwapStatus.DECLINED);

        var adminOutgoing = listOutgoing(f.admin(), f.roundId());
        assertThat(adminOutgoing).extracting(SwapRequestSummary::id).containsExactly(pending.id());

        var adminIncoming = listIncoming(f.admin(), f.roundId());
        assertThat(adminIncoming).isEmpty();

        var adaIncoming = listIncoming(f.ada(), f.roundId());
        assertThat(adaIncoming).extracting(SwapRequestSummary::id).containsExactly(pending.id());

        var adaOutgoing = listOutgoing(f.ada(), f.roundId());
        assertThat(adaOutgoing).isEmpty();
    }

    // The veteran/newcomer rule

    @Test
    void newcomerInASecondRoundCannotMoveAheadOfAVeteran() throws Exception {
        var f = setUp(PAST_START);

        // Complete round 1 so admin/ada/eze become veterans of this group.
        for (Cycle cycle : f.cycles()) {
            contributeAll(f, cycle);
            payout(f.admin(), cycle.getId(), f.beneficiaryOf(cycle).id(), newKey());
        }

        var newcomer = registerUser("Newcomer");
        addToGroup(f.admin(), f.groupId(), newcomer);

        var round2Id = createRound(f.admin(), f.groupId(), AMOUNT, LocalDate.now().plusMonths(6).withDayOfMonth(28));
        addParticipant(f.admin(), round2Id, f.admin());
        addParticipant(f.admin(), round2Id, f.ada());
        addParticipant(f.admin(), round2Id, f.eze());
        addParticipant(f.admin(), round2Id, newcomer);

        activate(f.admin(), round2Id);

        // Activation always places every veteran ahead of every newcomer, so this
        // is rejected regardless of which veteran the newcomer targets.
        requestSwapAndExpectDetail(newcomer, round2Id, f.admin().id(), 409,
                "Members in their first round cannot move ahead of members who have completed a round");
    }

    // HTTP helpers — swaps

    private SwapRequestSummary requestSwap(TestUser caller, UUID roundId, UUID targetUserId) throws Exception {
        var result = mockMvc.perform(post("/rounds/" + roundId + "/swaps")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSwapRequest(targetUserId))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), SwapRequestSummary.class);
    }

    private void requestSwapAndExpect(TestUser caller, UUID roundId, UUID targetUserId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/rounds/" + roundId + "/swaps")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSwapRequest(targetUserId))))
                .andExpect(status().is(expectedStatus));
    }

    private void requestSwapAndExpectDetail(TestUser caller, UUID roundId, UUID targetUserId,
                                             int expectedStatus, String expectedDetail) throws Exception {
        mockMvc.perform(post("/rounds/" + roundId + "/swaps")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSwapRequest(targetUserId))))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.detail").value(expectedDetail));
    }

    private SwapRequestSummary decline(TestUser caller, UUID swapId) throws Exception {
        var result = mockMvc.perform(post("/swaps/" + swapId + "/decline")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), SwapRequestSummary.class);
    }

    private void declineAndExpect(TestUser caller, UUID swapId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/swaps/" + swapId + "/decline")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    private SwapRequestSummary cancel(TestUser caller, UUID swapId) throws Exception {
        var result = mockMvc.perform(post("/swaps/" + swapId + "/cancel")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), SwapRequestSummary.class);
    }

    private void cancelAndExpect(TestUser caller, UUID swapId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/swaps/" + swapId + "/cancel")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    private List<SwapRequestSummary> listForRound(TestUser caller, UUID roundId) throws Exception {
        var result = mockMvc.perform(get("/rounds/" + roundId + "/swaps")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return List.of(objectMapper.readValue(result.getResponse().getContentAsString(), SwapRequestSummary[].class));
    }

    private List<SwapRequestSummary> listIncoming(TestUser caller, UUID roundId) throws Exception {
        var result = mockMvc.perform(get("/rounds/" + roundId + "/swaps/incoming")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return List.of(objectMapper.readValue(result.getResponse().getContentAsString(), SwapRequestSummary[].class));
    }

    private List<SwapRequestSummary> listOutgoing(TestUser caller, UUID roundId) throws Exception {
        var result = mockMvc.perform(get("/rounds/" + roundId + "/swaps/outgoing")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return List.of(objectMapper.readValue(result.getResponse().getContentAsString(), SwapRequestSummary[].class));
    }

    // HTTP helpers — payouts and contributions

    private void contributeAll(Fixture f, Cycle cycle) throws Exception {
        for (TestUser member : f.members()) {
            contribute(member, cycle.getId(), newKey());
        }
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

    private void payout(TestUser caller, UUID cycleId,
                        UUID expectedBeneficiaryUserId, String key) throws Exception {
        mockMvc.perform(post("/cycles/" + cycleId + "/payout")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PayoutRequest(PayoutMethod.ONLINE, expectedBeneficiaryUserId))))
                .andExpect(status().isCreated());
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
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

        Map<UUID, TestUser> userByParticipantId = detail.participants().stream()
                .collect(Collectors.toMap(ParticipantSummary::id, p -> usersById.get(p.user().id())));

        var cycles = cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId);

        return new Fixture(groupId, roundId, admin, ada, eze, members, cycles, userByParticipantId);
    }

    private TestUser registerUser(String fullName) throws Exception {
        String phone = "0908%07d".formatted(PHONE_COUNTER.incrementAndGet());
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
