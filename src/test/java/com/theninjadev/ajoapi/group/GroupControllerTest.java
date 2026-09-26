package com.theninjadev.ajoapi.group;

import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.auth.AuthResponse;
import com.theninjadev.ajoapi.auth.RegisterRequest;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import java.time.Instant;
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
class GroupControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

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

    private UUID inviteAndExpect(TestUser admin, UUID groupId, String phone, int expectedStatus) throws Exception {
        var request = new InviteMemberRequest(phone);
        var result = mockMvc.perform(post("/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        if (expectedStatus != 201)
            return null;
        GroupInviteSummary summary = objectMapper.readValue(
                result.getResponse().getContentAsString(), GroupInviteSummary.class);
        return summary.id();
    }

    private void acceptInvite(TestUser invitee, UUID inviteId) throws Exception {
        mockMvc.perform(post("/groups/invites/" + inviteId + "/accept")
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void createGroupMakesCreatorAdmin() throws Exception {
        var a = registerUser("08021110001", "Alice");
        var groupId = createGroup(a, "Alice's Ajo");

        mockMvc.perform(get("/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].role").value("ADMIN"))
                .andExpect(jsonPath("$[0].user.id").value(a.id().toString()));
    }

    @Test
    void nonMemberGetsNotFoundForGroupTheyAreNotIn() throws Exception {
        var a = registerUser("08021110002", "Alice");
        var b = registerUser("08021110003", "Bob");
        var groupId = createGroup(a, "Alice's Ajo");

        mockMvc.perform(get("/groups/" + groupId)
                        .header("Authorization", "Bearer " + b.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void memberCannotUpdateGroup() throws Exception {
        var a = registerUser("08021110004", "Alice");
        var b = registerUser("08021110005", "Bob");
        var groupId = createGroup(a, "Alice's Ajo");
        var inviteId = inviteAndExpect(a, groupId, b.phone(), 201);
        acceptInvite(b, inviteId);

        var request = new UpdateGroupRequest("New name", "New description");
        mockMvc.perform(patch("/groups/" + groupId)
                        .header("Authorization", "Bearer " + b.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberCannotInviteMember() throws Exception {
        var a = registerUser("08021110006", "Alice");
        var b = registerUser("08021110007", "Bob");
        var groupId = createGroup(a, "Alice's Ajo");
        var inviteId = inviteAndExpect(a, groupId, b.phone(), 201);
        acceptInvite(b, inviteId);

        inviteAndExpect(b, groupId, "08021110099", 403);
    }

    @Test
    void invitingPhoneOfExistingMemberIsRejected() throws Exception {
        var a = registerUser("08021110008", "Alice");
        var b = registerUser("08021110009", "Bob");
        var groupId = createGroup(a, "Alice's Ajo");
        var inviteId = inviteAndExpect(a, groupId, b.phone(), 201);
        acceptInvite(b, inviteId);

        inviteAndExpect(a, groupId, b.phone(), 409);
    }

    @Test
    void duplicatePendingInviteIsRejected() throws Exception {
        var a = registerUser("08021110010", "Alice");
        var groupId = createGroup(a, "Alice's Ajo");

        inviteAndExpect(a, groupId, "08021110098", 201);
        inviteAndExpect(a, groupId, "08021110098", 409);
    }

    @Test
    void memberWhoLeavesCanBeReinvitedSuccessfully() throws Exception {
        var a = registerUser("08021110011", "Alice");
        var b = registerUser("08021110012", "Bob");
        var groupId = createGroup(a, "Alice's Ajo");
        var inviteId = inviteAndExpect(a, groupId, b.phone(), 201);
        acceptInvite(b, inviteId);

        mockMvc.perform(post("/groups/" + groupId + "/leave")
                        .header("Authorization", "Bearer " + b.accessToken()))
                .andExpect(status().isNoContent());

        inviteAndExpect(a, groupId, b.phone(), 201);
    }

    @Test
    void inviteCannotBeAcceptedTwice() throws Exception {
        var a = registerUser("08021110013", "Alice");
        var b = registerUser("08021110014", "Bob");
        var groupId = createGroup(a, "Alice's Ajo");
        var inviteId = inviteAndExpect(a, groupId, b.phone(), 201);
        acceptInvite(b, inviteId);

        mockMvc.perform(post("/groups/invites/" + inviteId + "/accept")
                        .header("Authorization", "Bearer " + b.accessToken()))
                .andExpect(status().isConflict());
    }

    @Test
    void acceptInviteWithMismatchedPhoneReturnsNotFound() throws Exception {
        var a = registerUser("08021110015", "Alice");
        var c = registerUser("08021110016", "Carol");
        var groupId = createGroup(a, "Alice's Ajo");
        var inviteId = inviteAndExpect(a, groupId, "08021110097", 201);

        mockMvc.perform(post("/groups/invites/" + inviteId + "/accept")
                        .header("Authorization", "Bearer " + c.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void lastAdminCannotLeaveGroupWithOtherMembersPresent() throws Exception {
        var a = registerUser("08021110017", "Alice");
        var b = registerUser("08021110018", "Bob");
        var groupId = createGroup(a, "Alice's Ajo");
        var inviteId = inviteAndExpect(a, groupId, b.phone(), 201);
        acceptInvite(b, inviteId);

        mockMvc.perform(post("/groups/" + groupId + "/leave")
                        .header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isConflict());
    }

    @Test
    void soleAdminAndSoleMemberCanLeaveGroup() throws Exception {
        var a = registerUser("08021110019", "Alice");
        var groupId = createGroup(a, "Alice's Ajo");

        mockMvc.perform(post("/groups/" + groupId + "/leave")
                        .header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isNoContent());
    }

    @Test
    void adminCannotRemoveSelf() throws Exception {
        var a = registerUser("08021110020", "Alice");
        var groupId = createGroup(a, "Alice's Ajo");

        mockMvc.perform(delete("/groups/" + groupId + "/members/" + a.id())
                        .header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptingInviteCreatesMembershipWithRoleMember() throws Exception {
        var a = registerUser("08021110021", "Alice");
        var b = registerUser("08021110022", "Bob");
        var groupId = createGroup(a, "Alice's Ajo");
        var inviteId = inviteAndExpect(a, groupId, b.phone(), 201);
        acceptInvite(b, inviteId);

        mockMvc.perform(get("/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + b.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.user.id == '" + b.id() + "')].role").value("MEMBER"));
    }
}
