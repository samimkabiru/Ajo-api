package com.theninjadev.ajoapi.payout;

import com.theninjadev.ajoapi.auth.AuthResponse;
import com.theninjadev.ajoapi.auth.RegisterRequest;
import com.theninjadev.ajoapi.group.CreateGroupRequest;
import com.theninjadev.ajoapi.group.GroupInviteSummary;
import com.theninjadev.ajoapi.group.GroupSummary;
import com.theninjadev.ajoapi.group.InviteMemberRequest;
import com.theninjadev.ajoapi.round.AddParticipantRequest;
import com.theninjadev.ajoapi.round.CreateRoundRequest;
import com.theninjadev.ajoapi.round.RoundDetail;
import com.theninjadev.ajoapi.round.RoundSummary;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PayoutReadTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record TestUser(UUID id, String phone, String accessToken) {}
    private record ActivatedSetup(UUID roundId, UUID cycleId, TestUser admin, TestUser ada, TestUser eze) {}

    @Test
    void nonGroupMemberCannotListPayoutsForRound() throws Exception {
        var setup = setUpActivatedRound("08071110");
        var outsider = registerUser("08071119999", "Outsider");

        mockMvc.perform(get("/rounds/" + setup.roundId() + "/payouts")
                        .header("Authorization", "Bearer " + outsider.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void freshlyActivatedRoundHasNoPayouts() throws Exception {
        var setup = setUpActivatedRound("08072220");

        var result = mockMvc.perform(get("/rounds/" + setup.roundId() + "/payouts")
                        .header("Authorization", "Bearer " + setup.admin().accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        PayoutSummary[] payouts = objectMapper.readValue(
                result.getResponse().getContentAsString(), PayoutSummary[].class);

        assertThat(payouts).isEmpty();
    }

    @Test
    void unpaidCycleHasNoPayout() throws Exception {
        var setup = setUpActivatedRound("08073330");

        mockMvc.perform(get("/cycles/" + setup.cycleId() + "/payout")
                        .header("Authorization", "Bearer " + setup.admin().accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void freshlyActivatedRoundHasNoShortfallClaims() throws Exception {
        var setup = setUpActivatedRound("08074440");

        var result = mockMvc.perform(get("/rounds/" + setup.roundId() + "/shortfall-claims")
                        .header("Authorization", "Bearer " + setup.admin().accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        ShortfallClaimSummary[] claims = objectMapper.readValue(
                result.getResponse().getContentAsString(), ShortfallClaimSummary[].class);

        assertThat(claims).isEmpty();
    }

    private ActivatedSetup setUpActivatedRound(String phonePrefix) throws Exception {
        var admin = registerUser(phonePrefix + "001", "Alice");
        var ada = registerUser(phonePrefix + "002", "Ada");
        var eze = registerUser(phonePrefix + "003", "Eze");

        var groupId = createGroup(admin, "Alice's Ajo");
        addToGroup(admin, groupId, ada);
        addToGroup(admin, groupId, eze);

        var roundId = createRound(admin, groupId, 1000000L, LocalDate.of(2026, 3, 31));
        addParticipant(admin, roundId, admin);
        addParticipant(admin, roundId, ada);
        addParticipant(admin, roundId, eze);

        var detail = activate(admin, roundId);
        return new ActivatedSetup(roundId, detail.cycles().get(0).id(), admin, ada, eze);
    }

    private void addParticipant(TestUser admin, UUID roundId, TestUser participant) throws Exception {
        var request = new AddParticipantRequest(participant.id());
        mockMvc.perform(post("/rounds/" + roundId + "/participants")
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private RoundDetail activate(TestUser caller, UUID roundId) throws Exception {
        var result = mockMvc.perform(post("/rounds/" + roundId + "/activate")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), RoundDetail.class);
    }

    private TestUser registerUser(String rawPhone, String fullName) throws Exception {
        var request = new RegisterRequest(rawPhone, "password123", fullName, null);
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
        GroupSummary summary = objectMapper.readValue(
                result.getResponse().getContentAsString(), GroupSummary.class);
        return summary.id();
    }

    private void addToGroup(TestUser admin, UUID groupId, TestUser invitee) throws Exception {
        var request = new InviteMemberRequest(invitee.phone());
        var result = mockMvc.perform(post("/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        GroupInviteSummary invite = objectMapper.readValue(
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
        RoundSummary summary = objectMapper.readValue(
                result.getResponse().getContentAsString(), RoundSummary.class);
        return summary.id();
    }
}
