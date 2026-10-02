package com.theninjadev.ajoapi.group;

import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.auth.AuthResponse;
import com.theninjadev.ajoapi.auth.RegisterRequest;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.AdjustableClock;
import com.theninjadev.ajoapi.testsupport.AdjustableClockConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AdjustableClockConfig.class)
class GroupControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

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

    // Inviter's name on invites. The invitee is not a member yet, so they get a name only:
    // never the inviter's phone or email.

    @Test
    void myInvitesCarryTheRightInviterNameForEachGroup() throws Exception {
        var alice = registerUser("08021110030", "Alice Okafor");
        var bola = registerUser("08021110031", "Bola Adeyemi");
        var invitee = registerUser("08021110032", "Ike");
        var aliceGroup = createGroup(alice, "Alice's Ajo");
        var bolaGroup = createGroup(bola, "Bola's Ajo");
        inviteAndExpect(alice, aliceGroup, invitee.phone(), 201);
        inviteAndExpect(bola, bolaGroup, invitee.phone(), 201);

        var body = mockMvc.perform(get("/groups/my-invites")
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.groupId == '" + aliceGroup + "')].inviterName").value("Alice Okafor"))
                .andExpect(jsonPath("$[?(@.groupId == '" + aliceGroup + "')].invitedBy").value(alice.id().toString()))
                .andExpect(jsonPath("$[?(@.groupId == '" + bolaGroup + "')].inviterName").value("Bola Adeyemi"))
                .andExpect(jsonPath("$[?(@.groupId == '" + bolaGroup + "')].invitedBy").value(bola.id().toString()))
                .andReturn().getResponse().getContentAsString();

        assertNoInviterContactDetails(body, alice, bola);
    }

    @Test
    void inviteResponseCarriesTheInviterName() throws Exception {
        var alice = registerUser("08021110033", "Alice Okafor");
        var invitee = registerUser("08021110034", "Ike");
        var groupId = createGroup(alice, "Alice's Ajo");

        var body = mockMvc.perform(post("/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteMemberRequest(invitee.phone()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inviterName").value("Alice Okafor"))
                .andExpect(jsonPath("$.invitedBy").value(alice.id().toString()))
                .andReturn().getResponse().getContentAsString();

        assertNoInviterContactDetails(body, alice);
    }

    @Test
    void declineAndRevokeResponsesCarryTheInviterName() throws Exception {
        var alice = registerUser("08021110035", "Alice Okafor");
        var decliner = registerUser("08021110036", "Ike");
        var other = registerUser("08021110037", "Uche");
        var groupId = createGroup(alice, "Alice's Ajo");
        var declined = inviteAndExpect(alice, groupId, decliner.phone(), 201);
        var revoked = inviteAndExpect(alice, groupId, other.phone(), 201);

        var declineBody = mockMvc.perform(post("/groups/invites/" + declined + "/decline")
                        .header("Authorization", "Bearer " + decliner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviterName").value("Alice Okafor"))
                .andExpect(jsonPath("$.invitedBy").value(alice.id().toString()))
                .andReturn().getResponse().getContentAsString();

        var revokeBody = mockMvc.perform(post("/groups/invites/" + revoked + "/revoke")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviterName").value("Alice Okafor"))
                .andExpect(jsonPath("$.invitedBy").value(alice.id().toString()))
                .andReturn().getResponse().getContentAsString();

        assertNoInviterContactDetails(declineBody, alice);
        assertNoInviterContactDetails(revokeBody, alice);
    }

    // GET /groups/{groupId}/invites: the admin's view of what the group has sent, so a pending
    // invite can still be found and revoked once the invite response is gone.

    @Test
    void inviteIdTakenFromTheListCanBeRevoked() throws Exception {
        var alice = registerUser("08021110040", "Alice Okafor");
        var invitee = registerUser("08021110041", "Ike");
        var groupId = createGroup(alice, "Alice's Ajo");
        inviteAndExpect(alice, groupId, invitee.phone(), 201);  // id deliberately discarded

        var listed = listGroupInvites(alice, groupId);
        assertThat(listed).hasSize(1);
        assertThat(listed.getFirst().status()).isEqualTo(InviteStatus.PENDING);

        mockMvc.perform(post("/groups/invites/" + listed.getFirst().id() + "/revoke")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        assertThat(listGroupInvites(alice, groupId))
                .extracting(GroupInviteSummary::status)
                .containsExactly(InviteStatus.REVOKED);
    }

    @Test
    void groupInvitesListEveryStatusWithTheInviterNameAndNoInviterContactDetails() throws Exception {
        var alice = registerUser("08021110042", "Alice Okafor");
        var accepter = registerUser("08021110043", "Ike");
        var decliner = registerUser("08021110044", "Uche");
        var revokee = registerUser("08021110045", "Ngozi");
        var pending = registerUser("08021110046", "Tunde");
        var groupId = createGroup(alice, "Alice's Ajo");

        acceptInvite(accepter, inviteAndExpect(alice, groupId, accepter.phone(), 201));
        var declined = inviteAndExpect(alice, groupId, decliner.phone(), 201);
        mockMvc.perform(post("/groups/invites/" + declined + "/decline")
                        .header("Authorization", "Bearer " + decliner.accessToken()))
                .andExpect(status().isOk());
        var revoked = inviteAndExpect(alice, groupId, revokee.phone(), 201);
        mockMvc.perform(post("/groups/invites/" + revoked + "/revoke")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk());
        inviteAndExpect(alice, groupId, pending.phone(), 201);

        var body = mockMvc.perform(get("/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, GroupInviteSummary> byPhone = Arrays.stream(objectMapper.readValue(body, GroupInviteSummary[].class))
                .collect(Collectors.toMap(GroupInviteSummary::phone, Function.identity()));
        assertThat(byPhone).hasSize(4);
        assertThat(byPhone.get(accepter.phone()).status()).isEqualTo(InviteStatus.ACCEPTED);
        assertThat(byPhone.get(decliner.phone()).status()).isEqualTo(InviteStatus.DECLINED);
        assertThat(byPhone.get(revokee.phone()).status()).isEqualTo(InviteStatus.REVOKED);
        assertThat(byPhone.get(pending.phone()).status()).isEqualTo(InviteStatus.PENDING);
        assertThat(byPhone.values()).allSatisfy(invite -> {
            assertThat(invite.groupId()).isEqualTo(groupId);
            assertThat(invite.invitedBy()).isEqualTo(alice.id());
            assertThat(invite.inviterName()).isEqualTo("Alice Okafor");
        });

        assertNoInviterContactDetails(body, alice);
    }

    /**
     * Two admins can't happen through the API (there is no promotion), so the second is made
     * directly. Proves the batched name lookup maps each invite to its own inviter.
     */
    @Test
    void groupInvitesFromTwoInvitersEachCarryTheirOwnInviterName() throws Exception {
        var alice = registerUser("08021110047", "Alice Okafor");
        var bola = registerUser("08021110048", "Bola Adeyemi");
        var first = registerUser("08021110049", "Ike");
        var second = registerUser("08021110050", "Uche");
        var groupId = createGroup(alice, "Alice's Ajo");
        acceptInvite(bola, inviteAndExpect(alice, groupId, bola.phone(), 201));
        jdbc.update("UPDATE group_members SET role = 'ADMIN' WHERE group_id = ? AND user_id = ?", groupId, bola.id());

        inviteAndExpect(alice, groupId, first.phone(), 201);
        inviteAndExpect(bola, groupId, second.phone(), 201);

        var body = mockMvc.perform(get("/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.phone == '" + first.phone() + "')].inviterName").value("Alice Okafor"))
                .andExpect(jsonPath("$[?(@.phone == '" + first.phone() + "')].invitedBy").value(alice.id().toString()))
                .andExpect(jsonPath("$[?(@.phone == '" + second.phone() + "')].inviterName").value("Bola Adeyemi"))
                .andExpect(jsonPath("$[?(@.phone == '" + second.phone() + "')].invitedBy").value(bola.id().toString()))
                .andReturn().getResponse().getContentAsString();

        // Bola's own phone is legitimately present as the invitee of the first invite, so only
        // Alice's is checked here; the email check still covers both.
        assertNoInviterContactDetails(body, alice);
    }

    @Test
    void groupInvitesAreListedNewestFirst() throws Exception {
        var alice = registerUser("08021110051", "Alice Okafor");
        var groupId = createGroup(alice, "Alice's Ajo");
        // Stepped clock, so the order under test is staged rather than left to microsecond timing.
        var oldest = inviteAndExpect(alice, groupId, "08021110052", 201);
        ((AdjustableClock) clock).advanceBy(Duration.ofMinutes(1));
        var middle = inviteAndExpect(alice, groupId, "08021110053", 201);
        ((AdjustableClock) clock).advanceBy(Duration.ofMinutes(1));
        var newest = inviteAndExpect(alice, groupId, "08021110054", 201);

        assertThat(listGroupInvites(alice, groupId))
                .extracting(GroupInviteSummary::id)
                .containsExactly(newest, middle, oldest);
    }

    @Test
    void groupWithNoInvitesListsNone() throws Exception {
        var alice = registerUser("08021110055", "Alice Okafor");
        var groupId = createGroup(alice, "Alice's Ajo");

        assertThat(listGroupInvites(alice, groupId)).isEmpty();
    }

    @Test
    void onlyAnAdminCanListAGroupsInvites() throws Exception {
        var alice = registerUser("08021110056", "Alice Okafor");
        var member = registerUser("08021110057", "Ike");
        var outsider = registerUser("08021110058", "Uche");
        var groupId = createGroup(alice, "Alice's Ajo");
        acceptInvite(member, inviteAndExpect(alice, groupId, member.phone(), 201));

        mockMvc.perform(get("/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + member.accessToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + outsider.accessToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/groups/" + UUID.randomUUID() + "/invites")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isNotFound());
    }

    private List<GroupInviteSummary> listGroupInvites(TestUser caller, UUID groupId) throws Exception {
        var body = mockMvc.perform(get("/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return List.of(objectMapper.readValue(body, GroupInviteSummary[].class));
    }

    /** The invite's own phone is the invitee's; nothing in the body may be the inviter's phone or any email. */
    private void assertNoInviterContactDetails(String body, TestUser... inviters) {
        for (TestUser inviter : inviters)
            assertThat(body).doesNotContain(inviter.phone());
        assertThat(body).doesNotContainIgnoringCase("email");
    }
}
