package com.theninjadev.ajoapi.testsupport;

import com.theninjadev.ajoapi.auth.AuthResponse;
import com.theninjadev.ajoapi.auth.RegisterRequest;
import com.theninjadev.ajoapi.contribution.ContributeRequest;
import com.theninjadev.ajoapi.contribution.ContributionSummary;
import com.theninjadev.ajoapi.exit.BuyInSummary;
import com.theninjadev.ajoapi.exit.ExitRequestSummary;
import com.theninjadev.ajoapi.exit.ExposureSummary;
import com.theninjadev.ajoapi.exit.RefundSummary;
import com.theninjadev.ajoapi.group.CreateGroupRequest;
import com.theninjadev.ajoapi.group.GroupInviteSummary;
import com.theninjadev.ajoapi.group.GroupSummary;
import com.theninjadev.ajoapi.group.InviteMemberRequest;
import com.theninjadev.ajoapi.payout.PayoutMethod;
import com.theninjadev.ajoapi.payout.PayoutRequest;
import com.theninjadev.ajoapi.payout.PayoutSummary;
import com.theninjadev.ajoapi.payout.ShortfallClaimSummary;
import com.theninjadev.ajoapi.round.AddParticipantRequest;
import com.theninjadev.ajoapi.round.CreateRoundRequest;
import com.theninjadev.ajoapi.round.RoundDetail;
import com.theninjadev.ajoapi.round.RoundSummary;
import com.theninjadev.ajoapi.swap.CreateSwapRequest;
import com.theninjadev.ajoapi.swap.SwapRequestSummary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP helpers shared by the integration tests. Not a Spring bean — construct it in a
 * {@code @BeforeEach} from the test's own MockMvc and ObjectMapper.
 *
 * <p>Each call comes in two forms: the happy-path one asserts success and returns the
 * parsed body; the {@code ...AndExpect} one asserts the given status and returns nothing.
 */
public class ApiTestClient {

    // Globally unique phone numbers — the container is shared and nothing rolls back.
    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger();

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    public ApiTestClient(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    // Auth and groups

    public TestUser registerUser(String fullName) throws Exception {
        String phone = "0909%07d".formatted(PHONE_COUNTER.incrementAndGet());
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

    public UUID createGroup(TestUser owner, String name) throws Exception {
        var request = new CreateGroupRequest(name, "description");
        var result = mockMvc.perform(post("/groups")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), GroupSummary.class).id();
    }

    /** Invites {@code invitee} by phone and has them accept. */
    public void addToGroup(TestUser admin, UUID groupId, TestUser invitee) throws Exception {
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

    // Rounds

    public UUID createRound(TestUser admin, UUID groupId, long amountKobo, LocalDate firstPayoutDate) throws Exception {
        var request = new CreateRoundRequest(amountKobo, firstPayoutDate);
        var result = mockMvc.perform(post("/groups/" + groupId + "/rounds")
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), RoundSummary.class).id();
    }

    public void addParticipant(TestUser admin, UUID roundId, TestUser participant) throws Exception {
        mockMvc.perform(post("/rounds/" + roundId + "/participants")
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddParticipantRequest(participant.id()))))
                .andExpect(status().isCreated());
    }

    public RoundDetail activate(TestUser caller, UUID roundId) throws Exception {
        var result = mockMvc.perform(post("/rounds/" + roundId + "/activate")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), RoundDetail.class);
    }

    public void activateAndExpect(TestUser caller, UUID roundId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/rounds/" + roundId + "/activate")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    // Contributions and payouts

    public ContributionSummary contribute(TestUser caller, UUID cycleId, long amountKobo,
                                          UUID targetUserId, String idempotencyKey) throws Exception {
        var result = mockMvc.perform(post("/cycles/" + cycleId + "/contributions")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ContributeRequest(amountKobo, targetUserId, null))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ContributionSummary.class);
    }

    public void contributeAndExpect(TestUser caller, UUID cycleId, long amountKobo,
                                    UUID targetUserId, String idempotencyKey, int expectedStatus) throws Exception {
        mockMvc.perform(post("/cycles/" + cycleId + "/contributions")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ContributeRequest(amountKobo, targetUserId, null))))
                .andExpect(status().is(expectedStatus));
    }

    public PayoutSummary payout(TestUser caller, UUID cycleId,
                                UUID expectedBeneficiaryUserId, String idempotencyKey) throws Exception {
        var result = mockMvc.perform(post("/cycles/" + cycleId + "/payout")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PayoutRequest(PayoutMethod.ONLINE, expectedBeneficiaryUserId))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), PayoutSummary.class);
    }

    public void payoutAndExpect(TestUser caller, UUID cycleId, UUID expectedBeneficiaryUserId,
                                String idempotencyKey, int expectedStatus) throws Exception {
        mockMvc.perform(post("/cycles/" + cycleId + "/payout")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PayoutRequest(PayoutMethod.ONLINE, expectedBeneficiaryUserId))))
                .andExpect(status().is(expectedStatus));
    }

    // Swaps

    public SwapRequestSummary requestSwap(TestUser caller, UUID roundId, UUID targetUserId) throws Exception {
        var result = mockMvc.perform(post("/rounds/" + roundId + "/swaps")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSwapRequest(targetUserId))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), SwapRequestSummary.class);
    }

    public void requestSwapAndExpect(TestUser caller, UUID roundId, UUID targetUserId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/rounds/" + roundId + "/swaps")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSwapRequest(targetUserId))))
                .andExpect(status().is(expectedStatus));
    }

    public void requestSwapAndExpectDetail(TestUser caller, UUID roundId, UUID targetUserId,
                                           int expectedStatus, String expectedDetail) throws Exception {
        mockMvc.perform(post("/rounds/" + roundId + "/swaps")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSwapRequest(targetUserId))))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.detail").value(expectedDetail));
    }

    public SwapRequestSummary accept(TestUser caller, UUID swapId) throws Exception {
        var result = mockMvc.perform(post("/swaps/" + swapId + "/accept")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), SwapRequestSummary.class);
    }

    public void acceptAndExpect(TestUser caller, UUID swapId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/swaps/" + swapId + "/accept")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    /** Returns the raw status instead of asserting — for use from worker threads in race tests. */
    public int acceptStatus(TestUser caller, UUID swapId) throws Exception {
        return mockMvc.perform(post("/swaps/" + swapId + "/accept")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    public SwapRequestSummary decline(TestUser caller, UUID swapId) throws Exception {
        var result = mockMvc.perform(post("/swaps/" + swapId + "/decline")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), SwapRequestSummary.class);
    }

    public void declineAndExpect(TestUser caller, UUID swapId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/swaps/" + swapId + "/decline")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    public SwapRequestSummary cancel(TestUser caller, UUID swapId) throws Exception {
        var result = mockMvc.perform(post("/swaps/" + swapId + "/cancel")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), SwapRequestSummary.class);
    }

    public void cancelAndExpect(TestUser caller, UUID swapId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/swaps/" + swapId + "/cancel")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    public List<SwapRequestSummary> listSwapsForRound(TestUser caller, UUID roundId) throws Exception {
        return listSwaps(caller, "/rounds/" + roundId + "/swaps");
    }

    public List<SwapRequestSummary> listIncomingSwaps(TestUser caller, UUID roundId) throws Exception {
        return listSwaps(caller, "/rounds/" + roundId + "/swaps/incoming");
    }

    public List<SwapRequestSummary> listOutgoingSwaps(TestUser caller, UUID roundId) throws Exception {
        return listSwaps(caller, "/rounds/" + roundId + "/swaps/outgoing");
    }

    private List<SwapRequestSummary> listSwaps(TestUser caller, String path) throws Exception {
        var result = mockMvc.perform(get(path)
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return List.of(objectMapper.readValue(result.getResponse().getContentAsString(), SwapRequestSummary[].class));
    }

    // Exits

    public ExitRequestSummary requestExit(TestUser caller, UUID roundId) throws Exception {
        var result = mockMvc.perform(post("/rounds/" + roundId + "/exit")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ExitRequestSummary.class);
    }

    public List<ExitRequestSummary> listExitsForRound(TestUser caller, UUID roundId) throws Exception {
        var result = mockMvc.perform(get("/rounds/" + roundId + "/exits")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return List.of(objectMapper.readValue(result.getResponse().getContentAsString(), ExitRequestSummary[].class));
    }

    public void listExitsForRoundAndExpect(TestUser caller, UUID roundId, int expectedStatus) throws Exception {
        mockMvc.perform(get("/rounds/" + roundId + "/exits")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    public void getMyExitAndExpect(TestUser caller, UUID roundId, int expectedStatus) throws Exception {
        mockMvc.perform(get("/rounds/" + roundId + "/exit/mine")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    public ExitRequestSummary cancelExit(TestUser caller, UUID exitId) throws Exception {
        var result = mockMvc.perform(post("/exits/" + exitId + "/cancel")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ExitRequestSummary.class);
    }

    public void cancelExitAndExpect(TestUser caller, UUID exitId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/exits/" + exitId + "/cancel")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    // Buy-ins

    public List<BuyInSummary> listBuyInsForRound(TestUser caller, UUID roundId) throws Exception {
        var result = mockMvc.perform(get("/rounds/" + roundId + "/buy-ins")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return List.of(objectMapper.readValue(result.getResponse().getContentAsString(), BuyInSummary[].class));
    }

    public void listBuyInsForRoundAndExpect(TestUser caller, UUID roundId, int expectedStatus) throws Exception {
        mockMvc.perform(get("/rounds/" + roundId + "/buy-ins")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    public void getBuyInForExitAndExpectDetail(TestUser caller, UUID exitId,
                                               int expectedStatus, String expectedDetail) throws Exception {
        mockMvc.perform(get("/exits/" + exitId + "/buy-in")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.detail").value(expectedDetail));
    }

    // Settlement

    public List<RefundSummary> listRefundsForRound(TestUser caller, UUID roundId) throws Exception {
        var result = mockMvc.perform(get("/rounds/" + roundId + "/refunds")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return List.of(objectMapper.readValue(result.getResponse().getContentAsString(), RefundSummary[].class));
    }

    public void listRefundsForRoundAndExpect(TestUser caller, UUID roundId, int expectedStatus) throws Exception {
        mockMvc.perform(get("/rounds/" + roundId + "/refunds")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    public void getRefundForExitAndExpectDetail(TestUser caller, UUID exitId,
                                                int expectedStatus, String expectedDetail) throws Exception {
        mockMvc.perform(get("/exits/" + exitId + "/refund")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.detail").value(expectedDetail));
    }

    public List<ShortfallClaimSummary> listOpenShortfallClaims(TestUser caller, UUID roundId) throws Exception {
        var result = mockMvc.perform(get("/rounds/" + roundId + "/shortfall-claims/open")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return List.of(objectMapper.readValue(result.getResponse().getContentAsString(), ShortfallClaimSummary[].class));
    }

    public void listOpenShortfallClaimsAndExpect(TestUser caller, UUID roundId, int expectedStatus) throws Exception {
        mockMvc.perform(get("/rounds/" + roundId + "/shortfall-claims/open")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    public void getVacantCycleStatusAndExpect(TestUser caller, UUID cycleId, int expectedStatus) throws Exception {
        mockMvc.perform(get("/cycles/" + cycleId + "/settlement")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
    }

    public ExposureSummary getExposure(TestUser caller, UUID participantId) throws Exception {
        var result = mockMvc.perform(get("/participants/" + participantId + "/exposure")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ExposureSummary.class);
    }
}
