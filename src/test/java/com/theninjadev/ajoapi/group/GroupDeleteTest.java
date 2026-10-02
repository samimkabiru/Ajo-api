package com.theninjadev.ajoapi.group;

import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.round.CycleSummary;
import com.theninjadev.ajoapi.round.RoundDetail;
import com.theninjadev.ajoapi.round.RoundStatus;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.ApiTestClient;
import com.theninjadev.ajoapi.testsupport.TestUser;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
class GroupDeleteTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;                  // ₦10,000
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);

    private static final String ARCHIVED = "This circle is archived and can no longer be changed";
    private static final String IN_PROGRESS = "This circle has a round in progress and cannot be removed";
    private static final String FORMING = "Cancel the round that's still forming before archiving this circle.";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper, userRepository);
    }

    // ------------------------------------------------------------------
    // Hard delete
    // ------------------------------------------------------------------

    @Test
    void groupWithNoRoundsIsHardDeletedWithItsMembersAndInvites() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var invitee = client.registerUser("Ike");
        var groupId = client.createGroup(admin, "Mistyped");
        client.addToGroup(admin, groupId, ada);
        invite(admin, groupId, invitee);

        deleteGroup(admin, groupId, 204);

        assertThat(count("SELECT count(*) FROM groups WHERE id = ?", groupId)).isZero();
        assertThat(count("SELECT count(*) FROM group_members WHERE group_id = ?", groupId)).isZero();
        assertThat(count("SELECT count(*) FROM group_invites WHERE group_id = ?", groupId)).isZero();
    }

    @Test
    void groupWithOnlyFormingAndCancelledRoundsIsHardDeletedWithThem() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var groupId = client.createGroup(admin, "Never started");
        client.addToGroup(admin, groupId, ada);

        var cancelled = client.createRound(admin, groupId, AMOUNT, PAST_START);
        client.addParticipant(admin, cancelled, admin);
        client.addParticipant(admin, cancelled, ada);
        mockMvc.perform(authed(post("/rounds/" + cancelled + "/cancel"), admin)).andExpect(status().isOk());

        var forming = client.createRound(admin, groupId, AMOUNT, PAST_START);
        client.addParticipant(admin, forming, admin);
        client.addParticipant(admin, forming, ada);

        deleteGroup(admin, groupId, 204);

        assertThat(count("SELECT count(*) FROM groups WHERE id = ?", groupId)).isZero();
        assertThat(count("SELECT count(*) FROM rounds WHERE group_id = ?", groupId)).isZero();
        assertThat(count("SELECT count(*) FROM round_participants WHERE round_id IN (?, ?)", cancelled, forming))
                .isZero();
    }

    @Test
    void hardDeleteTouchesNoLedgerRow() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var groupId = client.createGroup(admin, "Never started");
        client.addToGroup(admin, groupId, ada);
        var forming = client.createRound(admin, groupId, AMOUNT, PAST_START);
        client.addParticipant(admin, forming, admin);
        client.addParticipant(admin, forming, ada);

        long accountsBefore = count("SELECT count(*) FROM ledger_accounts");
        long entriesBefore = count("SELECT count(*) FROM ledger_entries");

        deleteGroup(admin, groupId, 204);

        assertThat(count("SELECT count(*) FROM ledger_accounts")).isEqualTo(accountsBefore);
        assertThat(count("SELECT count(*) FROM ledger_entries")).isEqualTo(entriesBefore);
    }

    // ------------------------------------------------------------------
    // Archive
    // ------------------------------------------------------------------

    @Test
    void groupWithCompletedRoundIsArchivedAndEveryRowSurvives() throws Exception {
        var f = completedGroup();
        Map<String, Long> before = footprint(f);

        var archived = deleteGroupExpectingArchive(f.admin(), f.groupId());

        assertThat(archived.id()).isEqualTo(f.groupId());
        assertThat(archived.archivedAt()).isNotNull();
        assertThat(footprint(f)).isEqualTo(before);
        assertThat(before.values()).allMatch(n -> n > 0);
    }

    @Test
    void archivedGroupLeavesTheDefaultListButIsReachableWithTheFilter() throws Exception {
        var f = completedGroup();
        deleteGroupExpectingArchive(f.admin(), f.groupId());

        assertThat(listGroupIds(f.ada(), "/groups")).doesNotContain(f.groupId());
        assertThat(listGroupIds(f.ada(), "/groups?archived=false")).doesNotContain(f.groupId());
        assertThat(listGroupIds(f.ada(), "/groups?archived=true")).containsExactly(f.groupId());
    }

    @Test
    void activeGroupsAreNotListedUnderTheArchivedFilter() throws Exception {
        var admin = client.registerUser("Alice");
        var groupId = client.createGroup(admin, "Live");

        assertThat(listGroupIds(admin, "/groups")).containsExactly(groupId);
        assertThat(listGroupIds(admin, "/groups?archived=true")).isEmpty();
    }

    @Test
    void archivedGroupIsStillReturnedByIdWithArchivedAt() throws Exception {
        var f = completedGroup();
        var archived = deleteGroupExpectingArchive(f.admin(), f.groupId());

        var result = mockMvc.perform(authed(get("/groups/" + f.groupId()), f.ada()))
                .andExpect(status().isOk())
                .andReturn();
        var detail = objectMapper.readValue(result.getResponse().getContentAsString(), GroupDetail.class);

        assertThat(detail.archivedAt()).isEqualTo(archived.archivedAt());
        assertThat(detail.members()).hasSize(2);
    }

    @Test
    void activeGroupReportsNullArchivedAt() throws Exception {
        var admin = client.registerUser("Alice");
        var groupId = client.createGroup(admin, "Live");

        mockMvc.perform(authed(get("/groups/" + groupId), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
    }

    @Test
    void everyWriteOnAnArchivedGroupIsRefusedWithTheSameException() throws Exception {
        var f = completedGroup();
        deleteGroupExpectingArchive(f.admin(), f.groupId());
        var g = f.groupId();

        expectArchived(authed(patch("/groups/" + g), f.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateGroupRequest("Renamed", "x"))));
        var stranger = client.registerUser("Stranger");
        expectArchived(authed(post("/groups/" + g + "/invites"), f.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new InviteMemberRequest(stranger.phone()))));
        expectArchived(authed(post("/groups/" + g + "/rounds"), f.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contributionAmountKobo\":1000000,\"firstPayoutDate\":\"2027-01-31\"}"));
        expectArchived(authed(delete("/groups/" + g + "/members/" + f.ada().id()), f.admin()));
        expectArchived(authed(post("/groups/" + g + "/leave"), f.ada()));
        expectArchived(authed(post("/groups/invites/" + f.inviteId() + "/accept"), f.invitee()));
        expectArchived(authed(post("/groups/invites/" + f.inviteId() + "/decline"), f.invitee()));
        expectArchived(authed(post("/groups/invites/" + f.inviteId() + "/revoke"), f.admin()));

        // Nothing moved: name, membership, invite and rounds are as they were.
        assertThat(jdbc.queryForObject("SELECT name FROM groups WHERE id = ?", String.class, g))
                .isEqualTo("Alice's Ajo");
        assertThat(count("SELECT count(*) FROM group_members WHERE group_id = ?", g)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM group_invites WHERE id = ?", String.class, f.inviteId()))
                .isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM rounds WHERE group_id = ?", g)).isEqualTo(1);
    }

    @Test
    void everyReadOnAnArchivedGroupStillWorks() throws Exception {
        var f = completedGroup();
        deleteGroupExpectingArchive(f.admin(), f.groupId());

        for (TestUser caller : List.of(f.admin(), f.ada())) {
            mockMvc.perform(authed(get("/groups/" + f.groupId()), caller)).andExpect(status().isOk());
            mockMvc.perform(authed(get("/groups/" + f.groupId() + "/members"), caller))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
            mockMvc.perform(authed(get("/groups/" + f.groupId() + "/rounds"), caller))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
            mockMvc.perform(authed(get("/rounds/" + f.roundId()), caller))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.cycles.length()").value(2));
            mockMvc.perform(authed(get("/rounds/" + f.roundId() + "/payouts"), caller))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
            mockMvc.perform(authed(get("/rounds/" + f.roundId() + "/contributions"), caller))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(4));
        }
    }

    @Test
    void archivedGroupStillListsItsInvitesToTheAdmin() throws Exception {
        var f = completedGroup();
        deleteGroupExpectingArchive(f.admin(), f.groupId());

        var result = mockMvc.perform(authed(get("/groups/" + f.groupId() + "/invites"), f.admin()))
                .andExpect(status().isOk())
                .andReturn();
        var invites = List.of(objectMapper.readValue(
                result.getResponse().getContentAsString(), GroupInviteSummary[].class));

        // Ada's accepted invite from addToGroup, and Ike's that is still pending.
        assertThat(invites).extracting(GroupInviteSummary::phone)
                .containsExactlyInAnyOrder(f.ada().phone(), f.invitee().phone());
        assertThat(invites).filteredOn(invite -> invite.id().equals(f.inviteId()))
                .singleElement()
                .extracting(GroupInviteSummary::status)
                .isEqualTo(InviteStatus.PENDING);
    }

    @Test
    void pendingInviteToAnArchivedGroupIsHiddenFromTheInvitee() throws Exception {
        var f = completedGroup();
        assertThat(myInviteIds(f.invitee())).contains(f.inviteId());

        deleteGroupExpectingArchive(f.admin(), f.groupId());

        assertThat(myInviteIds(f.invitee())).doesNotContain(f.inviteId());
    }

    @Test
    void formingRoundAlongsideHistoryBlocksArchivingUntilCancelled() throws Exception {
        var f = completedGroup();
        var forming = client.createRound(f.admin(), f.groupId(), AMOUNT, LocalDate.of(2027, 1, 31));

        mockMvc.perform(authed(delete("/groups/" + f.groupId()), f.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(FORMING));
        assertThat(archivedAt(f.groupId())).isNull();

        mockMvc.perform(authed(post("/rounds/" + forming + "/cancel"), f.admin())).andExpect(status().isOk());

        var archived = deleteGroupExpectingArchive(f.admin(), f.groupId());
        assertThat(archived.archivedAt()).isNotNull();
    }

    @Test
    void deletingAnArchivedGroupAgainIsIdempotent() throws Exception {
        var f = completedGroup();
        var first = deleteGroupExpectingArchive(f.admin(), f.groupId());

        var second = deleteGroupExpectingArchive(f.admin(), f.groupId());

        assertThat(second.archivedAt()).isEqualTo(first.archivedAt());
        assertThat(count("SELECT count(*) FROM groups WHERE id = ?", f.groupId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Refusal
    // ------------------------------------------------------------------

    @Test
    void groupWithActiveRoundIsRefusedAndNothingChanges() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var groupId = client.createGroup(admin, "Live");
        client.addToGroup(admin, groupId, ada);
        var roundId = client.createRound(admin, groupId, AMOUNT, PAST_START);
        client.addParticipant(admin, roundId, admin);
        client.addParticipant(admin, roundId, ada);
        client.activate(admin, roundId);

        mockMvc.perform(authed(delete("/groups/" + groupId), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(IN_PROGRESS));

        assertThat(count("SELECT count(*) FROM groups WHERE id = ?", groupId)).isEqualTo(1);
        assertThat(archivedAt(groupId)).isNull();
        assertThat(count("SELECT count(*) FROM group_members WHERE group_id = ?", groupId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM rounds WHERE id = ?", String.class, roundId))
                .isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------
    // Authorization
    // ------------------------------------------------------------------

    @Test
    void nonAdminMemberIsForbiddenAndNothingChanges() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var groupId = client.createGroup(admin, "Alice's Ajo");
        client.addToGroup(admin, groupId, ada);

        deleteGroup(ada, groupId, 403);

        assertThat(count("SELECT count(*) FROM groups WHERE id = ?", groupId)).isEqualTo(1);
        assertThat(archivedAt(groupId)).isNull();
        assertThat(count("SELECT count(*) FROM group_members WHERE group_id = ?", groupId)).isEqualTo(2);
    }

    @Test
    void nonAdminMemberCannotArchiveEither() throws Exception {
        var f = completedGroup();

        deleteGroup(f.ada(), f.groupId(), 403);

        assertThat(archivedAt(f.groupId())).isNull();
    }

    @Test
    void nonMemberGetsNotFound() throws Exception {
        var admin = client.registerUser("Alice");
        var outsider = client.registerUser("Olu");
        var groupId = client.createGroup(admin, "Alice's Ajo");

        deleteGroup(outsider, groupId, 404);

        assertThat(count("SELECT count(*) FROM groups WHERE id = ?", groupId)).isEqualTo(1);
    }

    @Test
    void missingGroupGetsNotFound() throws Exception {
        var admin = client.registerUser("Alice");

        deleteGroup(admin, UUID.randomUUID(), 404);
    }

    // ------------------------------------------------------------------
    // Delete vs activation: each ordering forced, then the race itself
    // ------------------------------------------------------------------

    @Test
    void activateThenDeleteRefusesAndLeavesTheActiveRound() throws Exception {
        var r = formingRoundReadyToActivate("Activated first");

        client.activate(r.admin(), r.roundId());
        mockMvc.perform(authed(delete("/groups/" + r.groupId()), r.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(IN_PROGRESS));

        assertActivatedAndIntact(r, "activate then delete");
    }

    @Test
    void deleteThenActivateFindsNothing() throws Exception {
        var r = formingRoundReadyToActivate("Deleted first");

        deleteGroup(r.admin(), r.groupId(), 204);
        client.activateAndExpect(r.admin(), r.roundId(), 404);

        assertDeletedWithItsRound(r, "delete then activate");
    }

    /**
     * Both orderings are proven by the two tests above. This one proves only that no
     * interleaving produces anything else — it does not care which side wins, since the
     * scheduler may consistently favour one.
     */
    @Test
    void deleteRacingActivationNeverLeavesAnIncoherentState() throws Exception {
        Map<String, Integer> tally = new TreeMap<>();

        for (int i = 0; i < 8; i++) {
            var r = formingRoundReadyToActivate("Race " + i);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            Callable<Integer> deletes = () -> {
                ready.countDown();
                go.await();
                return statusOf(authed(delete("/groups/" + r.groupId()), r.admin()));
            };
            Callable<Integer> activates = () -> {
                ready.countDown();
                go.await();
                return statusOf(authed(post("/rounds/" + r.roundId() + "/activate"), r.admin()));
            };

            int deleteStatus;
            int activateStatus;
            try {
                var d = pool.submit(deletes);
                var a = pool.submit(activates);

                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();

                deleteStatus = d.get(30, TimeUnit.SECONDS);
                activateStatus = a.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            String outcome = "activate=" + activateStatus + ",delete=" + deleteStatus;
            tally.merge(outcome, 1, Integer::sum);
            String context = "iteration %d, outcomes so far %s".formatted(i, tally);

            assertThat(outcome).as(context).isIn(ACTIVATE_WON, DELETE_WON);
            if (outcome.equals(ACTIVATE_WON))
                assertActivatedAndIntact(r, context);
            else
                assertDeletedWithItsRound(r, context);
        }

        System.out.println("deleteRacingActivation outcomes: " + tally);
        assertThat(tally.keySet()).as("outcomes %s", tally).isSubsetOf(ACTIVATE_WON, DELETE_WON);
    }

    private static final String ACTIVATE_WON = "activate=200,delete=409";
    private static final String DELETE_WON = "activate=404,delete=204";

    private record FormingRound(UUID groupId, UUID roundId, TestUser admin) {}

    /** A two-member group with a FORMING round that has everything it needs to activate. */
    private FormingRound formingRoundReadyToActivate(String name) throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var groupId = client.createGroup(admin, name);
        client.addToGroup(admin, groupId, ada);
        var roundId = client.createRound(admin, groupId, AMOUNT, PAST_START);
        client.addParticipant(admin, roundId, admin);
        client.addParticipant(admin, roundId, ada);
        return new FormingRound(groupId, roundId, admin);
    }

    /** Activation won: the group is untouched and the round is fully active. */
    private void assertActivatedAndIntact(FormingRound r, String context) {
        assertThat(count("SELECT count(*) FROM groups WHERE id = ?", r.groupId())).as(context).isEqualTo(1);
        assertThat(archivedAt(r.groupId())).as(context).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM rounds WHERE id = ?", String.class, r.roundId()))
                .as(context).isEqualTo(RoundStatus.ACTIVE.name());
        assertThat(roundPools(r.roundId())).as(context).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM cycles WHERE round_id = ?", r.roundId())).as(context).isEqualTo(2);
    }

    /** Delete won: the round went with the group, and nothing was ever activated. */
    private void assertDeletedWithItsRound(FormingRound r, String context) {
        assertThat(count("SELECT count(*) FROM groups WHERE id = ?", r.groupId())).as(context).isZero();
        assertThat(count("SELECT count(*) FROM rounds WHERE id = ?", r.roundId())).as(context).isZero();
        assertThat(count("SELECT count(*) FROM round_participants WHERE round_id = ?", r.roundId()))
                .as(context).isZero();
        assertThat(count("SELECT count(*) FROM cycles WHERE round_id = ?", r.roundId())).as(context).isZero();
        assertThat(roundPools(r.roundId())).as(context).isZero();
    }

    private long roundPools(UUID roundId) {
        return count("SELECT count(*) FROM ledger_accounts WHERE account_type = 'ROUND_POOL' AND owner_id = ?",
                roundId);
    }

    // ------------------------------------------------------------------
    // Fixtures and helpers
    // ------------------------------------------------------------------

    private record CompletedGroup(UUID groupId, UUID roundId, TestUser admin, TestUser ada,
                                  TestUser invitee, UUID inviteId) {}

    /** A two-member group whose only round ran to COMPLETED, plus one pending invite. */
    private CompletedGroup completedGroup() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var invitee = client.registerUser("Ike");
        var groupId = client.createGroup(admin, "Alice's Ajo");
        client.addToGroup(admin, groupId, ada);

        var roundId = client.createRound(admin, groupId, AMOUNT, PAST_START);
        client.addParticipant(admin, roundId, admin);
        client.addParticipant(admin, roundId, ada);
        RoundDetail detail = client.activate(admin, roundId);

        Map<UUID, TestUser> usersById = Map.of(admin.id(), admin, ada.id(), ada);
        for (CycleSummary cycle : detail.cycles()) {
            for (TestUser member : List.of(admin, ada)) {
                client.contribute(member, cycle.id(), AMOUNT, member.id(), UUID.randomUUID().toString());
            }
            var beneficiary = usersById.get(cycle.beneficiary().id());
            client.payout(beneficiary, cycle.id(), beneficiary.id(), UUID.randomUUID().toString());
        }
        assertThat(jdbc.queryForObject("SELECT status FROM rounds WHERE id = ?", String.class, roundId))
                .isEqualTo(RoundStatus.COMPLETED.name());

        var inviteId = invite(admin, groupId, invitee);
        return new CompletedGroup(groupId, roundId, admin, ada, invitee, inviteId);
    }

    /** Row counts for everything the group owns, directly or through its rounds. */
    private Map<String, Long> footprint(CompletedGroup f) {
        UUID g = f.groupId();
        return Map.of(
                "groups", count("SELECT count(*) FROM groups WHERE id = ?", g),
                "members", count("SELECT count(*) FROM group_members WHERE group_id = ?", g),
                "invites", count("SELECT count(*) FROM group_invites WHERE group_id = ?", g),
                "rounds", count("SELECT count(*) FROM rounds WHERE group_id = ?", g),
                "participants", count("""
                        SELECT count(*) FROM round_participants rp JOIN rounds r ON r.id = rp.round_id
                        WHERE r.group_id = ?""", g),
                "cycles", count("SELECT count(*) FROM cycles c JOIN rounds r ON r.id = c.round_id WHERE r.group_id = ?", g),
                "contributions", count("""
                        SELECT count(*) FROM contributions x JOIN cycles c ON c.id = x.cycle_id
                        JOIN rounds r ON r.id = c.round_id WHERE r.group_id = ?""", g),
                "payouts", count("""
                        SELECT count(*) FROM payouts p JOIN cycles c ON c.id = p.cycle_id
                        JOIN rounds r ON r.id = c.round_id WHERE r.group_id = ?""", g),
                "ledgerAccounts", count("""
                        SELECT count(*) FROM ledger_accounts WHERE owner_id = ?
                           OR owner_id IN (SELECT id FROM round_participants WHERE round_id = ?)""",
                        f.roundId(), f.roundId()),
                "ledgerEntries", count("SELECT count(*) FROM ledger_entries"));
    }

    private UUID invite(TestUser admin, UUID groupId, TestUser invitee) throws Exception {
        var result = mockMvc.perform(authed(post("/groups/" + groupId + "/invites"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteMemberRequest(invitee.phone()))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), GroupInviteSummary.class).id();
    }

    private void deleteGroup(TestUser caller, UUID groupId, int expectedStatus) throws Exception {
        mockMvc.perform(authed(delete("/groups/" + groupId), caller))
                .andExpect(status().is(expectedStatus));
    }

    private GroupSummary deleteGroupExpectingArchive(TestUser caller, UUID groupId) throws Exception {
        var result = mockMvc.perform(authed(delete("/groups/" + groupId), caller))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), GroupSummary.class);
    }

    private void expectArchived(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(ARCHIVED));
    }

    private List<UUID> listGroupIds(TestUser caller, String path) throws Exception {
        var result = mockMvc.perform(authed(get(path), caller))
                .andExpect(status().isOk())
                .andReturn();
        return List.of(objectMapper.readValue(result.getResponse().getContentAsString(), GroupSummary[].class))
                .stream().map(GroupSummary::id).toList();
    }

    private List<UUID> myInviteIds(TestUser caller) throws Exception {
        var result = mockMvc.perform(authed(get("/groups/my-invites"), caller))
                .andExpect(status().isOk())
                .andReturn();
        return List.of(objectMapper.readValue(result.getResponse().getContentAsString(), GroupInviteSummary[].class))
                .stream().map(GroupInviteSummary::id).toList();
    }

    private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, TestUser caller) {
        return request.header("Authorization", "Bearer " + caller.accessToken());
    }

    private Object archivedAt(UUID groupId) {
        return jdbc.queryForObject("SELECT archived_at FROM groups WHERE id = ?", Object.class, groupId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
