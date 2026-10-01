package com.theninjadev.ajoapi.round;

import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.group.GroupRepository;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RoundDeleteTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;                  // ₦10,000
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);

    private static final String STARTED = "A round that has started cannot be deleted";
    private static final String ARCHIVED = "This circle is archived and can no longer be changed";
    private static final String ROUND_NOT_FOUND = "Round not found";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper, userRepository);
    }

    // ------------------------------------------------------------------
    // Deletes
    // ------------------------------------------------------------------

    @Test
    void formingRoundIsDeletedWithItsParticipants() throws Exception {
        var g = groupOfThree();
        var roundId = roundWithParticipants(g, g.admin(), g.ada());
        var before = untouchables();

        deleteRound(g.admin(), roundId, 204);

        assertThat(count("SELECT count(*) FROM rounds WHERE id = ?", roundId)).isZero();
        assertThat(participants(roundId)).isZero();
        assertThat(untouchables()).isEqualTo(before);
    }

    @Test
    void cancelledRoundIsDeleted() throws Exception {
        var g = groupOfThree();
        var roundId = roundWithParticipants(g, g.admin(), g.ada());
        cancel(g.admin(), roundId);
        var before = untouchables();

        deleteRound(g.admin(), roundId, 204);

        assertThat(count("SELECT count(*) FROM rounds WHERE id = ?", roundId)).isZero();
        assertThat(participants(roundId)).isZero();
        assertThat(untouchables()).isEqualTo(before);
    }

    // ------------------------------------------------------------------
    // Scoped to one round
    // ------------------------------------------------------------------

    @Test
    void deletingOneRoundLeavesTheGroupsOtherRoundAndItsParticipants() throws Exception {
        var g = groupOfThree();
        var cancelled = roundWithParticipants(g, g.admin(), g.ada(), g.eze());
        cancel(g.admin(), cancelled);
        var forming = roundWithParticipants(g, g.admin(), g.ada());

        deleteRound(g.admin(), forming, 204);

        assertThat(count("SELECT count(*) FROM rounds WHERE id = ?", forming)).isZero();
        assertThat(participants(forming)).isZero();
        assertThat(count("SELECT count(*) FROM rounds WHERE id = ?", cancelled)).isEqualTo(1);
        assertThat(participants(cancelled)).isEqualTo(3);

        deleteRound(g.admin(), cancelled, 204);

        assertThat(count("SELECT count(*) FROM rounds WHERE group_id = ?", g.groupId())).isZero();
        assertThat(participants(cancelled)).isZero();
    }

    @Test
    void deletingACancelledRoundLeavesTheCompletedRoundAndItsMoneyUntouched() throws Exception {
        var g = groupOfThree();
        var completed = completedRound(g);
        var cancelled = roundWithParticipants(g, g.admin(), g.ada());
        cancel(g.admin(), cancelled);
        var before = footprint(completed);

        deleteRound(g.admin(), cancelled, 204);

        assertThat(count("SELECT count(*) FROM rounds WHERE id = ?", cancelled)).isZero();
        assertThat(footprint(completed)).isEqualTo(before);
        assertThat(before.values()).allMatch(n -> n > 0);
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    @Test
    void activeRoundIsRefusedAndNothingIsDeleted() throws Exception {
        var g = groupOfThree();
        var roundId = roundWithParticipants(g, g.admin(), g.ada());
        client.activate(g.admin(), roundId);
        var before = footprint(roundId);

        expectDeleteRefused(g.admin(), roundId, STARTED);

        assertThat(footprint(roundId)).isEqualTo(before);
        assertThat(roundStatus(roundId)).isEqualTo("ACTIVE");
    }

    @Test
    void completedRoundIsRefusedAndNothingIsDeleted() throws Exception {
        var g = groupOfThree();
        var roundId = completedRound(g);
        var before = footprint(roundId);

        expectDeleteRefused(g.admin(), roundId, STARTED);

        assertThat(footprint(roundId)).isEqualTo(before);
        assertThat(roundStatus(roundId)).isEqualTo("COMPLETED");
    }

    @Test
    void cancelledRoundInAnArchivedGroupIsRefusedAsArchivedNotAsStarted() throws Exception {
        var g = groupOfThree();
        completedRound(g);
        var cancelled = roundWithParticipants(g, g.admin(), g.ada());
        cancel(g.admin(), cancelled);
        mockMvc.perform(authed(delete("/groups/" + g.groupId()), g.admin())).andExpect(status().isOk());

        expectDeleteRefused(g.admin(), cancelled, ARCHIVED);

        assertThat(roundStatus(cancelled)).isEqualTo("CANCELLED");
        assertThat(participants(cancelled)).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Authorization
    // ------------------------------------------------------------------

    @Test
    void nonAdminMemberIsForbiddenAndNothingChanges() throws Exception {
        var g = groupOfThree();
        var roundId = roundWithParticipants(g, g.admin(), g.ada());

        deleteRound(g.ada(), roundId, 403);

        assertThat(roundStatus(roundId)).isEqualTo("FORMING");
        assertThat(participants(roundId)).isEqualTo(2);
    }

    @Test
    void nonMemberGetsNotFoundAndNothingChanges() throws Exception {
        var g = groupOfThree();
        var roundId = roundWithParticipants(g, g.admin(), g.ada());
        var outsider = client.registerUser("Olu");

        deleteRound(outsider, roundId, 404);

        assertThat(roundStatus(roundId)).isEqualTo("FORMING");
        assertThat(participants(roundId)).isEqualTo(2);
    }

    @Test
    void missingRoundGetsNotFound() throws Exception {
        var admin = client.registerUser("Alice");

        deleteRound(admin, UUID.randomUUID(), 404);
    }

    // ------------------------------------------------------------------
    // Delete against activation
    // ------------------------------------------------------------------

    private static final String ACTIVATE_WON = "activate=200,delete=409";
    private static final String DELETE_WON = "activate=404,delete=204";

    @Test
    void activateThenDeleteRefusesAndLeavesTheActiveRound() throws Exception {
        var g = groupOfThree();
        var roundId = roundWithParticipants(g, g.admin(), g.ada());

        client.activate(g.admin(), roundId);
        expectDeleteRefused(g.admin(), roundId, STARTED);

        assertActivated(roundId, 2, "activate then delete");
    }

    @Test
    void deleteThenActivateFindsNothing() throws Exception {
        var g = groupOfThree();
        var roundId = roundWithParticipants(g, g.admin(), g.ada());

        deleteRound(g.admin(), roundId, 204);
        client.activateAndExpect(g.admin(), roundId, 404);

        assertGone(roundId, "delete then activate");
    }

    /** Both orderings are proven above; this proves only that no interleaving yields anything else. */
    @Test
    void deleteRacingActivationNeverLeavesAnIncoherentState() throws Exception {
        Map<String, Integer> tally = new TreeMap<>();

        for (int i = 0; i < 8; i++) {
            var g = groupOfThree();
            var roundId = roundWithParticipants(g, g.admin(), g.ada());

            int[] statuses = race(
                    () -> statusOf(authed(post("/rounds/" + roundId + "/activate"), g.admin())),
                    () -> statusOf(authed(delete("/rounds/" + roundId), g.admin())));

            String outcome = "activate=" + statuses[0] + ",delete=" + statuses[1];
            tally.merge(outcome, 1, Integer::sum);
            String context = "iteration %d, outcomes so far %s".formatted(i, tally);

            assertThat(outcome).as(context).isIn(ACTIVATE_WON, DELETE_WON);
            if (outcome.equals(ACTIVATE_WON))
                assertActivated(roundId, 2, context);
            else
                assertGone(roundId, context);
        }

        System.out.println("deleteRoundRacingActivation outcomes: " + tally);
    }

    // ------------------------------------------------------------------
    // Joining against activation — the race the group lock on round writes closes
    // ------------------------------------------------------------------

    @Test
    void addParticipantRacingActivationNeverLeavesAPositionlessParticipant() throws Exception {
        Map<String, Integer> tally = new TreeMap<>();

        for (int i = 0; i < 8; i++) {
            var g = groupOfThree();
            var roundId = roundWithParticipants(g, g.admin(), g.ada());

            int[] statuses = race(
                    () -> statusOf(authed(post("/rounds/" + roundId + "/activate"), g.admin())),
                    () -> statusOf(authed(post("/rounds/" + roundId + "/participants"), g.admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new AddParticipantRequest(g.eze().id())))));

            String outcome = "activate=" + statuses[0] + ",add=" + statuses[1];
            tally.merge(outcome, 1, Integer::sum);
            assertJoinRaceCoherent(roundId, outcome, "activate=200,add=409", "activate=200,add=201",
                    "iteration %d, outcomes so far %s".formatted(i, tally));
        }

        System.out.println("addParticipantRacingActivation outcomes: " + tally);
    }

    @Test
    void joinRacingActivationNeverLeavesAPositionlessParticipant() throws Exception {
        Map<String, Integer> tally = new TreeMap<>();

        for (int i = 0; i < 8; i++) {
            var g = groupOfThree();
            var roundId = roundWithParticipants(g, g.admin(), g.ada());

            int[] statuses = race(
                    () -> statusOf(authed(post("/rounds/" + roundId + "/activate"), g.admin())),
                    () -> statusOf(authed(post("/rounds/" + roundId + "/join"), g.eze())));

            String outcome = "activate=" + statuses[0] + ",join=" + statuses[1];
            tally.merge(outcome, 1, Integer::sum);
            assertJoinRaceCoherent(roundId, outcome, "activate=200,join=409", "activate=200,join=200",
                    "iteration %d, outcomes so far %s".formatted(i, tally));
        }

        System.out.println("joinRacingActivation outcomes: " + tally);
    }

    /** Either the join was turned away, or it made it into the schedule. Never in between. */
    private void assertJoinRaceCoherent(UUID roundId, String outcome, String refused, String joined, String context) {
        assertThat(outcome).as(context).isIn(refused, joined);
        assertActivated(roundId, outcome.equals(joined) ? 3 : 2, context);
    }

    @Test
    void addParticipantRacingDeleteNeverFails() throws Exception {
        Map<String, Integer> tally = new TreeMap<>();

        for (int i = 0; i < 8; i++) {
            var g = groupOfThree();
            var roundId = roundWithParticipants(g, g.admin(), g.ada());

            int[] statuses = race(
                    () -> statusOf(authed(post("/rounds/" + roundId + "/participants"), g.admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new AddParticipantRequest(g.eze().id())))),
                    () -> statusOf(authed(delete("/rounds/" + roundId), g.admin())));

            String outcome = "add=" + statuses[0] + ",delete=" + statuses[1];
            tally.merge(outcome, 1, Integer::sum);
            String context = "iteration %d, outcomes so far %s".formatted(i, tally);

            assertThat(outcome).as(context).isIn("add=201,delete=204", "add=404,delete=204");
            assertGone(roundId, context);
        }

        System.out.println("addParticipantRacingDelete outcomes: " + tally);
    }

    // ------------------------------------------------------------------
    // A round deleted while a writer waits on the group lock — staged, not raced
    // ------------------------------------------------------------------

    /**
     * Two parallel deletes may never reach this path: if the second starts after the first
     * commits, its 404 comes from getRoundOrThrow. So it is staged: this thread holds the group
     * lock, the delete loads the round and queues behind it, and only then is the round removed
     * and the lock released. The waiting delete's refresh hits a missing row.
     */
    @Test
    @Timeout(30)
    void deleteWaitingOnTheLockWhileItsRoundVanishesIs404() throws Exception {
        var g = groupOfThree();
        var roundId = roundWithParticipants(g, g.admin(), g.ada());

        MvcResult result = whileHoldingTheGroupLock(g.groupId(), roundId,
                authed(delete("/rounds/" + roundId), g.admin()));

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("detail").asString())
                .isEqualTo(ROUND_NOT_FOUND);
    }

    @Test
    @Timeout(30)
    void activateWaitingOnTheLockWhileItsRoundVanishesIs404() throws Exception {
        var g = groupOfThree();
        var roundId = roundWithParticipants(g, g.admin(), g.ada());

        MvcResult result = whileHoldingTheGroupLock(g.groupId(), roundId,
                authed(post("/rounds/" + roundId + "/activate"), g.admin()));

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("detail").asString())
                .isEqualTo(ROUND_NOT_FOUND);
        assertThat(roundPools(roundId)).isZero();
    }

    /**
     * Holds the group row lock, sends {@code request} on a worker, waits until it is queued on that
     * lock, deletes the round underneath it, then commits — releasing the worker into a round that
     * is gone. Returns the worker's result.
     */
    private MvcResult whileHoldingTheGroupLock(UUID groupId, UUID roundId, MockHttpServletRequestBuilder request)
            throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var tx = new TransactionTemplate(transactionManager);
            Future<MvcResult> pending = tx.execute(ignored -> {
                assertThat(groupRepository.findByIdForUpdate(groupId)).isPresent();

                Future<MvcResult> worker = pool.submit(() -> mockMvc.perform(request).andReturn());

                awaitLockWait();
                jdbc.update("DELETE FROM round_participants WHERE round_id = ?", roundId);
                jdbc.update("DELETE FROM rounds WHERE id = ?", roundId);
                return worker;                                              // commit releases the lock
            });
            return pending.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Waits, boundedly, until the worker is queued on the held lock. Matches any lock wait, as
     * LoginRateLimitTest does: nothing else in the test is running.
     */
    private void awaitLockWait() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database() and pid <> pg_backend_pid()
                      and wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiting != null && waiting > 0)
                return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the lock wait", e);
            }
        }
        throw new AssertionError("the request never queued behind the held group lock");
    }

    // ------------------------------------------------------------------
    // Fixtures and helpers
    // ------------------------------------------------------------------

    private record Group3(UUID groupId, TestUser admin, TestUser ada, TestUser eze) {}

    private Group3 groupOfThree() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var eze = client.registerUser("Eze");
        var groupId = client.createGroup(admin, "Alice's Ajo");
        client.addToGroup(admin, groupId, ada);
        client.addToGroup(admin, groupId, eze);
        return new Group3(groupId, admin, ada, eze);
    }

    private UUID roundWithParticipants(Group3 g, TestUser... participants) throws Exception {
        var roundId = client.createRound(g.admin(), g.groupId(), AMOUNT, PAST_START);
        for (TestUser p : participants)
            client.addParticipant(g.admin(), roundId, p);
        return roundId;
    }

    /** A two-participant round run to COMPLETED: everyone paid every cycle, every cycle paid out. */
    private UUID completedRound(Group3 g) throws Exception {
        var roundId = roundWithParticipants(g, g.admin(), g.ada());
        RoundDetail detail = client.activate(g.admin(), roundId);

        Map<UUID, TestUser> usersById = Map.of(g.admin().id(), g.admin(), g.ada().id(), g.ada());
        for (CycleSummary cycle : detail.cycles()) {
            for (TestUser member : List.of(g.admin(), g.ada()))
                client.contribute(member, cycle.id(), AMOUNT, member.id(), UUID.randomUUID().toString());
            var beneficiary = usersById.get(cycle.beneficiary().id());
            client.payout(beneficiary, cycle.id(), beneficiary.id(), UUID.randomUUID().toString());
        }
        assertThat(roundStatus(roundId)).isEqualTo("COMPLETED");
        return roundId;
    }

    private void cancel(TestUser admin, UUID roundId) throws Exception {
        mockMvc.perform(authed(post("/rounds/" + roundId + "/cancel"), admin)).andExpect(status().isOk());
    }

    /** Everything a never-activated round must not reach. */
    private Map<String, Long> untouchables() {
        return Map.of(
                "ledgerAccounts", count("SELECT count(*) FROM ledger_accounts"),
                "ledgerEntries", count("SELECT count(*) FROM ledger_entries"),
                "cycles", count("SELECT count(*) FROM cycles"));
    }

    /** Row counts for everything a round owns, directly or through its cycles and participants. */
    private Map<String, Long> footprint(UUID roundId) {
        return Map.of(
                "rounds", count("SELECT count(*) FROM rounds WHERE id = ?", roundId),
                "participants", participants(roundId),
                "cycles", count("SELECT count(*) FROM cycles WHERE round_id = ?", roundId),
                "contributions", count("""
                        SELECT count(*) FROM contributions x JOIN cycles c ON c.id = x.cycle_id
                        WHERE c.round_id = ?""", roundId),
                "payouts", count("""
                        SELECT count(*) FROM payouts p JOIN cycles c ON c.id = p.cycle_id
                        WHERE c.round_id = ?""", roundId),
                "ledgerAccounts", count("""
                        SELECT count(*) FROM ledger_accounts WHERE owner_id = ?
                           OR owner_id IN (SELECT id FROM round_participants WHERE round_id = ?)""",
                        roundId, roundId),
                "ledgerEntries", count("SELECT count(*) FROM ledger_entries"));
    }

    /** Activation went through: ACTIVE, every participant positioned, one cycle each. */
    private void assertActivated(UUID roundId, int expectedParticipants, String context) {
        assertThat(roundStatus(roundId)).as(context).isEqualTo("ACTIVE");
        assertThat(participants(roundId)).as(context).isEqualTo(expectedParticipants);
        assertThat(count("SELECT count(*) FROM round_participants WHERE round_id = ? AND payout_position IS NULL",
                roundId)).as("%s: positionless participants in an ACTIVE round", context).isZero();
        assertThat(count("SELECT count(*) FROM cycles WHERE round_id = ?", roundId))
                .as(context).isEqualTo(expectedParticipants);
        assertThat(count("""
                SELECT count(*) FROM round_participants rp
                WHERE rp.round_id = ?
                  AND (SELECT count(*) FROM cycles c WHERE c.beneficiary_id = rp.id) <> 1""", roundId))
                .as("%s: participants not the beneficiary of exactly one cycle", context).isZero();
        assertThat(roundPools(roundId)).as(context).isEqualTo(1);
    }

    /** The round and everything it owned is gone, and it never reached the ledger. */
    private void assertGone(UUID roundId, String context) {
        assertThat(count("SELECT count(*) FROM rounds WHERE id = ?", roundId)).as(context).isZero();
        assertThat(participants(roundId)).as(context).isZero();
        assertThat(count("SELECT count(*) FROM cycles WHERE round_id = ?", roundId)).as(context).isZero();
        assertThat(roundPools(roundId)).as(context).isZero();
    }

    /** Runs two requests released together from a start gate; returns their statuses in order. */
    private int[] race(Callable<Integer> first, Callable<Integer> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> {
                ready.countDown();
                go.await();
                return first.call();
            });
            var b = pool.submit(() -> {
                ready.countDown();
                go.await();
                return second.call();
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            return new int[] { a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS) };
        } finally {
            pool.shutdownNow();
        }
    }

    private void deleteRound(TestUser caller, UUID roundId, int expectedStatus) throws Exception {
        mockMvc.perform(authed(delete("/rounds/" + roundId), caller))
                .andExpect(status().is(expectedStatus));
    }

    private void expectDeleteRefused(TestUser caller, UUID roundId, String detail) throws Exception {
        mockMvc.perform(authed(delete("/rounds/" + roundId), caller))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(detail));
    }

    private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, TestUser caller) {
        return request.header("Authorization", "Bearer " + caller.accessToken());
    }

    private String roundStatus(UUID roundId) {
        return jdbc.queryForObject("SELECT status FROM rounds WHERE id = ?", String.class, roundId);
    }

    private long participants(UUID roundId) {
        return count("SELECT count(*) FROM round_participants WHERE round_id = ?", roundId);
    }

    private long roundPools(UUID roundId) {
        return count("SELECT count(*) FROM ledger_accounts WHERE account_type = 'ROUND_POOL' AND owner_id = ?",
                roundId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
