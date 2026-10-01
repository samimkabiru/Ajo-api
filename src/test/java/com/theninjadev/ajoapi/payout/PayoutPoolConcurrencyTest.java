package com.theninjadev.ajoapi.payout;

import com.theninjadev.ajoapi.auth.AuthResponse;
import com.theninjadev.ajoapi.auth.LoginRequest;
import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.contribution.ContributeRequest;
import com.theninjadev.ajoapi.ledger.AccountType;
import com.theninjadev.ajoapi.ledger.LedgerAccountRepository;
import com.theninjadev.ajoapi.ledger.LedgerService;
import com.theninjadev.ajoapi.round.CycleSummary;
import com.theninjadev.ajoapi.round.RoundDetail;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.AdjustableClock;
import com.theninjadev.ajoapi.testsupport.AdjustableClockConfig;
import com.theninjadev.ajoapi.testsupport.ApiTestClient;
import com.theninjadev.ajoapi.testsupport.TestUser;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two payouts on different cycles of the same round lock different cycle rows, so nothing
 * serialises them, and both read the round pool's balance before either posts. With two cycles
 * due at once and money in the pool for only one, the question is whether more can leave the
 * pool than ever entered it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdjustableClockConfig.class)
class PayoutPoolConcurrencyTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;          // ₦10,000 per member per cycle
    private static final int PARTICIPANTS = 3;
    private static final long ONE_FULL_POT = PARTICIPANTS * AMOUNT;
    private static final int ITERATIONS = 8;
    private static final String PASSWORD = "password123";   // ApiTestClient's registration password

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private LedgerAccountRepository ledgerAccountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper, userRepository);
    }

    private record Race(UUID roundId, CycleSummary cycle1, CycleSummary cycle2) {}

    private record Result(int iteration, String outcome, long contributed, long paidOut, long available) {}

    @Test
    @Timeout(180)
    void twoDueCyclesPaidInParallelNeverDrawMoreThanThePoolHolds() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var eze = client.registerUser("Eze");
        var members = List.of(admin, ada, eze);

        // Every round is set up before the clock moves, with cycles 1 and 2 both in the future.
        LocalDate firstPayout = LocalDate.now(clock).plusMonths(1);
        List<Race> races = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            var groupId = client.createGroup(admin, "Pool race " + i);
            client.addToGroup(admin, groupId, ada);
            client.addToGroup(admin, groupId, eze);
            var roundId = client.createRound(admin, groupId, AMOUNT, firstPayout);
            for (TestUser m : members)
                client.addParticipant(admin, roundId, m);
            RoundDetail detail = client.activate(admin, roundId);
            races.add(new Race(roundId, detail.cycles().get(0), detail.cycles().get(1)));
        }

        // Nobody touches the rounds for two months: cycles 1 and 2 are both past due and unpaid.
        ((AdjustableClock) clock).advanceBy(Duration.ofDays(70));
        assertThat(LocalDate.now(clock)).isAfter(races.getFirst().cycle2().payoutOn());
        String adminToken = login(admin);   // tokens issued before the jump have expired

        Map<String, Integer> tally = new TreeMap<>();
        List<Result> results = new ArrayList<>();

        for (int i = 0; i < ITERATIONS; i++) {
            Race race = races.get(i);

            // Exactly one full pot: everyone pays cycle 1, nobody pays cycle 2.
            for (TestUser m : members)
                contribute(adminToken, race.cycle1().id(), m.id());

            int[] statuses = inParallel(
                    () -> payoutStatus(adminToken, race.cycle1()),
                    () -> payoutStatus(adminToken, race.cycle2()));

            String outcome = "cycle1=" + statuses[0] + ",cycle2=" + statuses[1];
            tally.merge(outcome, 1, Integer::sum);

            long contributed = sum("""
                    SELECT coalesce(sum(x.amount_kobo), 0) FROM contributions x
                    JOIN cycles c ON c.id = x.cycle_id WHERE c.round_id = ?""", race.roundId());
            long paidOut = sum("""
                    SELECT coalesce(sum(p.actual_amount_kobo), 0) FROM payouts p
                    JOIN cycles c ON c.id = p.cycle_id WHERE c.round_id = ?""", race.roundId());
            long available = -ledgerService.balanceOf(poolAccountId(race.roundId()));

            results.add(new Result(i, outcome, contributed, paidOut, available));
        }

        System.out.println("payoutPoolRace outcomes: " + tally);
        results.forEach(r -> System.out.println("payoutPoolRace " + r));

        assertThat(results).allSatisfy(r -> assertThat(r.contributed()).isEqualTo(ONE_FULL_POT));
        assertThat(results)
                .as("pool went negative: more left the round than entered it. outcomes %s, details %s", tally, results)
                .allSatisfy(r -> assertThat(r.available()).isGreaterThanOrEqualTo(0));
        assertThat(results)
                .as("payouts exceeded contributions. outcomes %s, details %s", tally, results)
                .allSatisfy(r -> assertThat(r.paidOut()).isLessThanOrEqualTo(r.contributed()));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String login(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.phone(), PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    /** The admin pays on a member's behalf, which the contribution endpoint allows. */
    private void contribute(String adminToken, UUID cycleId, UUID memberId) throws Exception {
        mockMvc.perform(post("/cycles/" + cycleId + "/contributions")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ContributeRequest(AMOUNT, memberId, null))))
                .andExpect(status().isCreated());
    }

    /** Raw status, for worker threads. The admin records the payout for whoever the beneficiary is. */
    private int payoutStatus(String adminToken, CycleSummary cycle) throws Exception {
        return mockMvc.perform(post("/cycles/" + cycle.id() + "/payout")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PayoutRequest(PayoutMethod.ONLINE, cycle.beneficiary().id()))))
                .andReturn().getResponse().getStatus();
    }

    private int[] inParallel(Callable<Integer> first, Callable<Integer> second) throws Exception {
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

    private UUID poolAccountId(UUID roundId) {
        return ledgerAccountRepository.findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, roundId)
                .orElseThrow()
                .getId();
    }

    private long sum(String sql, UUID roundId) {
        return jdbc.queryForObject(sql, Long.class, roundId);
    }
}
