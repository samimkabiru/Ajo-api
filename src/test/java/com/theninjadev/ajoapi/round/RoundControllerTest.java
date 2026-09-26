package com.theninjadev.ajoapi.round;

import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.auth.AuthResponse;
import com.theninjadev.ajoapi.auth.RegisterRequest;
import com.theninjadev.ajoapi.group.CreateGroupRequest;
import com.theninjadev.ajoapi.group.GroupInviteSummary;
import com.theninjadev.ajoapi.group.GroupSummary;
import com.theninjadev.ajoapi.group.InviteMemberRequest;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RoundControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoundRepository roundRepository;

    private record TestUser(UUID id, String phone, String accessToken) {}

    private TestUser registerUser(String rawPhone, String fullName) throws Exception {
        var request = new RegisterRequest(rawPhone, "password123", fullName, null);
        var result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        AuthResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), AuthResponse.class);

        // Verified directly, as ApiTestClient does: this test is about groups, not verification.
        var user = userRepository.findById(response.user().id()).orElseThrow();
        user.markPhoneVerified(Instant.now());
        userRepository.save(user);

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

    private UUID createRoundAndExpect(TestUser admin, UUID groupId, int expectedStatus) throws Exception {
        var request = new CreateRoundRequest(1000000L, LocalDate.now().plusMonths(1));
        var result = mockMvc.perform(post("/groups/" + groupId + "/rounds")
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        if (expectedStatus != 201)
            return null;
        RoundSummary summary = objectMapper.readValue(
                result.getResponse().getContentAsString(), RoundSummary.class);
        return summary.id();
    }

    @Test
    void nonAdminCannotCreateRound() throws Exception {
        var a = registerUser("08031110001", "Alice");
        var b = registerUser("08031110002", "Bob");
        var groupId = createGroup(a, "Alice's Ajo");
        addToGroup(a, groupId, b);

        var request = new CreateRoundRequest(1000000L, LocalDate.now().plusMonths(1));
        mockMvc.perform(post("/groups/" + groupId + "/rounds")
                        .header("Authorization", "Bearer " + b.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void groupCannotHaveTwoFormingRounds() throws Exception {
        var a = registerUser("08031110003", "Alice");
        var groupId = createGroup(a, "Alice's Ajo");
        createRoundAndExpect(a, groupId, 201);

        createRoundAndExpect(a, groupId, 409);
    }

    @Test
    void nonGroupMemberCannotBeAddedAsParticipant() throws Exception {
        var a = registerUser("08031110004", "Alice");
        var outsider = registerUser("08031110005", "Carol");
        var groupId = createGroup(a, "Alice's Ajo");
        var roundId = createRoundAndExpect(a, groupId, 201);

        var request = new AddParticipantRequest(outsider.id());
        mockMvc.perform(post("/rounds/" + roundId + "/participants")
                        .header("Authorization", "Bearer " + a.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateParticipantIsRejected() throws Exception {
        var a = registerUser("08031110006", "Alice");
        var b = registerUser("08031110007", "Bob");
        var groupId = createGroup(a, "Alice's Ajo");
        addToGroup(a, groupId, b);
        var roundId = createRoundAndExpect(a, groupId, 201);

        var request = new AddParticipantRequest(b.id());
        mockMvc.perform(post("/rounds/" + roundId + "/participants")
                        .header("Authorization", "Bearer " + a.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/rounds/" + roundId + "/participants")
                        .header("Authorization", "Bearer " + a.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void activeRoundTermsCannotBeUpdated() throws Exception {
        var a = registerUser("08031110008", "Alice");
        var groupId = createGroup(a, "Alice's Ajo");
        var roundId = createRoundAndExpect(a, groupId, 201);

        Round round = roundRepository.findById(roundId).orElseThrow();
        roundRepository.save(Round.builder()
                .id(round.getId())
                .groupId(round.getGroupId())
                .contributionAmountKobo(round.getContributionAmountKobo())
                .status(RoundStatus.ACTIVE)
                .createdBy(round.getCreatedBy())
                .activatedAt(Instant.now())
                .firstPayoutDate(round.getFirstPayoutDate())
                .createdAt(round.getCreatedAt())
                .updatedAt(round.getUpdatedAt())
                .build());

        var request = new UpdateRoundRequest(2000000L, LocalDate.now().plusMonths(2));
        mockMvc.perform(patch("/rounds/" + roundId)
                        .header("Authorization", "Bearer " + a.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void participantsCannotBeAddedToNonFormingRound() throws Exception {
        var a = registerUser("08031110009", "Alice");
        var b = registerUser("08031110010", "Bob");
        var groupId = createGroup(a, "Alice's Ajo");
        addToGroup(a, groupId, b);
        var roundId = createRoundAndExpect(a, groupId, 201);

        mockMvc.perform(post("/rounds/" + roundId + "/cancel")
                        .header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isOk());

        var request = new AddParticipantRequest(b.id());
        mockMvc.perform(post("/rounds/" + roundId + "/participants")
                        .header("Authorization", "Bearer " + a.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void joinAndLeaveWorkForGroupMembers() throws Exception {
        var a = registerUser("08031110011", "Alice");
        var b = registerUser("08031110012", "Bob");
        var groupId = createGroup(a, "Alice's Ajo");
        addToGroup(a, groupId, b);
        var roundId = createRoundAndExpect(a, groupId, 201);

        mockMvc.perform(post("/rounds/" + roundId + "/join")
                        .header("Authorization", "Bearer " + b.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(b.id().toString()));

        mockMvc.perform(get("/rounds/" + roundId)
                        .header("Authorization", "Bearer " + b.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants.length()").value(1));

        mockMvc.perform(post("/rounds/" + roundId + "/leave")
                        .header("Authorization", "Bearer " + b.accessToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/rounds/" + roundId)
                        .header("Authorization", "Bearer " + b.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants.length()").value(0));
    }

    @Test
    void cancelMovesRoundToCancelled() throws Exception {
        var a = registerUser("08031110013", "Alice");
        var groupId = createGroup(a, "Alice's Ajo");
        var roundId = createRoundAndExpect(a, groupId, 201);

        mockMvc.perform(post("/rounds/" + roundId + "/cancel")
                        .header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/rounds/" + roundId)
                        .header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
