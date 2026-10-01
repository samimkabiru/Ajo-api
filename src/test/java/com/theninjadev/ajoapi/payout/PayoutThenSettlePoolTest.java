package com.theninjadev.ajoapi.payout;

import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.exit.ExitRequestRepository;
import com.theninjadev.ajoapi.exit.ExitStatus;
import com.theninjadev.ajoapi.exit.SettlementSummary;
import com.theninjadev.ajoapi.ledger.AccountType;
import com.theninjadev.ajoapi.ledger.LedgerAccountRepository;
import com.theninjadev.ajoapi.ledger.LedgerService;
import com.theninjadev.ajoapi.round.CycleRepository;
import com.theninjadev.ajoapi.round.CycleStatus;
import com.theninjadev.ajoapi.round.CycleSummary;
import com.theninjadev.ajoapi.round.ParticipantStatus;
import com.theninjadev.ajoapi.round.ParticipantSummary;
import com.theninjadev.ajoapi.round.RoundDetail;
import com.theninjadev.ajoapi.round.RoundParticipantRepository;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.ApiTestClient;
import com.theninjadev.ajoapi.testsupport.TestUser;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Payout caps at the round-wide pool, so a short cycle can take money paid into a vacant cycle.
 * Settlement must then spend no more than the pool still holds, and leave whatever it cannot
 * refund as a shortfall claim for the leaver. No concurrency involved: the order is enough.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PayoutThenSettlePoolTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;                          // ₦10,000 per member per cycle
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);  // every cycle already due

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private LedgerAccountRepository ledgerAccountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private ShortfallClaimRepository shortfallClaimRepository;
    @Autowired private ExitRequestRepository exitRequestRepository;
    @Autowired private RoundParticipantRepository roundParticipantRepository;
    @Autowired private CycleRepository cycleRepository;

    private ApiTestClient client;
    private final List<String> steps = new ArrayList<>();

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper, userRepository);
    }

    /** The pool is empty by settlement time: the whole refund becomes a claim. */
    @Test
    void payingAShortCycleFromTheVacantPotThenSettlingNeverOverdrawsThePool() throws Exception {
        var f = setUp();

        // 1. Month 1: everyone pays, its beneficiary collects the full pot.
        for (TestUser m : f.members())
            client.contribute(m, f.cycle1().id(), AMOUNT, m.id(), key());
        client.payout(f.admin(), f.cycle1().id(), f.first().id(), key());
        record(f, "1. month 1 paid in full and collected");

        // 2. The month-3 beneficiary leaves, owed the ₦10,000 they put in.
        requestExit(f);
        record(f, "2. month-3 beneficiary requests exit, owed 1 x AMOUNT");

        // 3. The two who remain pay into month 3, the leaver's cycle: the vacant pot.
        client.contribute(f.first(), f.cycle3().id(), AMOUNT, f.first().id(), key());
        client.contribute(f.second(), f.cycle3().id(), AMOUNT, f.second().id(), key());
        record(f, "3. two remaining members pay into vacant month 3");

        // 4. Month 2 is short: only its own beneficiary pays; the month-1 collector defaults.
        client.contribute(f.second(), f.cycle2().id(), AMOUNT, f.second().id(), key());
        record(f, "4. month 2 gets one contribution of three");

        // 5. Month 2 is paid out. It caps at the whole pool, so it reaches into the vacant pot.
        var payout2 = client.payout(f.admin(), f.cycle2().id(), f.second().id(), key());
        record(f, "5. month 2 paid out (actual %d)".formatted(payout2.actualAmountKobo()));

        // 6. The vacant month 3 is settled — against a pool that is now empty.
        var settlement = settle(f);
        record(f, "6. month 3 settled (%s)".formatted(describe(settlement)));

        assertPoolNeverNegative();

        // Nothing could be refunded, so the whole refund is owed by claim.
        assertThat(settlement.refund()).isNull();
        assertThat(settlement.remainingKobo()).isZero();
        assertLeaverOwedByClaim(f, AMOUNT);
    }

    /** The pool covers part of the refund: that part is paid, the rest becomes a claim. */
    @Test
    void settlementRefundsWhatThePoolHoldsAndClaimsTheRest() throws Exception {
        var f = setUp();

        // Month 1: everyone pays, its beneficiary collects.
        for (TestUser m : f.members())
            client.contribute(m, f.cycle1().id(), AMOUNT, m.id(), key());
        client.payout(f.admin(), f.cycle1().id(), f.first().id(), key());
        record(f, "1. month 1 paid in full and collected");

        // Month 2: the leaver pays too this time; only the month-1 collector defaults.
        client.contribute(f.leaver(), f.cycle2().id(), AMOUNT, f.leaver().id(), key());
        client.contribute(f.second(), f.cycle2().id(), AMOUNT, f.second().id(), key());
        record(f, "2. month 2 gets two contributions of three");

        // The leaver goes, owed the ₦20,000 they put in.
        requestExit(f);
        record(f, "3. month-3 beneficiary requests exit, owed 2 x AMOUNT");

        client.contribute(f.first(), f.cycle3().id(), AMOUNT, f.first().id(), key());
        client.contribute(f.second(), f.cycle3().id(), AMOUNT, f.second().id(), key());
        record(f, "4. two remaining members pay into vacant month 3");

        // Month 2 caps at the pool: its full ₦30,000, one third of it from the vacant pot.
        var payout2 = client.payout(f.admin(), f.cycle2().id(), f.second().id(), key());
        record(f, "5. month 2 paid out (actual %d)".formatted(payout2.actualAmountKobo()));

        var settlement = settle(f);
        record(f, "6. month 3 settled (%s)".formatted(describe(settlement)));

        assertPoolNeverNegative();

        // ₦10,000 was left in the pool: refunded. The other ₦10,000 is owed by claim.
        assertThat(settlement.refund()).isNotNull();
        assertThat(settlement.refund().expectedAmountKobo()).isEqualTo(2 * AMOUNT);
        assertThat(settlement.refund().actualAmountKobo()).isEqualTo(AMOUNT);
        assertLeaverOwedByClaim(f, AMOUNT);
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    private void assertPoolNeverNegative() {
        steps.forEach(s -> System.out.println("payoutThenSettle " + s));
        assertThat(steps)
                .as("the round pool went negative: more left it than ever entered. Steps:%n%s",
                        String.join("\n", steps))
                .allSatisfy(s -> assertThat(s).doesNotContain("available=-"));
    }

    /**
     * The leaver is out of the rotation, the group owes them {@code owed} by an open claim on the
     * vacant cycle, their exposure says exactly that, and the cycle stays VACANT while it is open.
     */
    private void assertLeaverOwedByClaim(Fixture f, long owed) throws Exception {
        var claim = shortfallClaimRepository.findByCycleId(f.cycle3().id()).orElseThrow();
        assertThat(claim.getParticipantId()).isEqualTo(f.leaverParticipantId());
        assertThat(claim.getAmountKobo()).isEqualTo(owed);
        assertThat(claim.getSettledAmountKobo()).isZero();

        var exit = exitRequestRepository
                .findByParticipantIdAndStatus(f.leaverParticipantId(), ExitStatus.COMPLETED);
        assertThat(exit).as("the exit completes even with the claim open").isPresent();
        assertThat(roundParticipantRepository.findById(f.leaverParticipantId()).orElseThrow().getStatus())
                .isEqualTo(ParticipantStatus.EXITED);

        assertThat(client.getExposure(f.admin(), f.leaverParticipantId()).exposureKobo())
                .as("negative exposure: the group owes the leaver exactly the claim")
                .isEqualTo(-owed);

        assertThat(cycleRepository.findById(f.cycle3().id()).orElseThrow().getStatus())
                .as("a cycle settles only once nothing is outstanding round-wide")
                .isEqualTo(CycleStatus.VACANT);
    }

    // ------------------------------------------------------------------
    // Fixtures and helpers
    // ------------------------------------------------------------------

    private record Fixture(UUID roundId, UUID poolAccountId, TestUser admin, List<TestUser> members,
                           CycleSummary cycle1, CycleSummary cycle2, CycleSummary cycle3,
                           TestUser first, TestUser second, TestUser leaver, UUID leaverParticipantId) {}

    /** Three members, one round, every cycle already due. Roles follow the payout order. */
    private Fixture setUp() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var eze = client.registerUser("Eze");
        var members = List.of(admin, ada, eze);

        var groupId = client.createGroup(admin, "Alice's Ajo");
        client.addToGroup(admin, groupId, ada);
        client.addToGroup(admin, groupId, eze);
        var roundId = client.createRound(admin, groupId, AMOUNT, PAST_START);
        for (TestUser m : members)
            client.addParticipant(admin, roundId, m);
        RoundDetail detail = client.activate(admin, roundId);

        Map<UUID, TestUser> byId = members.stream().collect(Collectors.toMap(TestUser::id, Function.identity()));
        CycleSummary cycle1 = detail.cycles().get(0);
        CycleSummary cycle2 = detail.cycles().get(1);
        CycleSummary cycle3 = detail.cycles().get(2);
        TestUser leaver = byId.get(cycle3.beneficiary().id());
        UUID leaverParticipantId = detail.participants().stream()
                .filter(p -> p.user().id().equals(leaver.id()))
                .map(ParticipantSummary::id)
                .findFirst().orElseThrow();
        UUID pool = ledgerAccountRepository.findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, roundId)
                .orElseThrow().getId();

        return new Fixture(roundId, pool, admin, members, cycle1, cycle2, cycle3,
                byId.get(cycle1.beneficiary().id()),     // collects month 1, then defaults on month 2
                byId.get(cycle2.beneficiary().id()),     // collects the short month 2
                leaver, leaverParticipantId);            // leaves before their month 3
    }

    private void requestExit(Fixture f) throws Exception {
        mockMvc.perform(post("/rounds/" + f.roundId() + "/exit")
                        .header("Authorization", "Bearer " + f.leaver().accessToken()))
                .andExpect(status().isCreated());
    }

    private SettlementSummary settle(Fixture f) throws Exception {
        var result = mockMvc.perform(post("/cycles/" + f.cycle3().id() + "/settle")
                        .header("Authorization", "Bearer " + f.admin().accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), SettlementSummary.class);
    }

    /** Logs the pool's available balance after a step. Negative means more left than came in. */
    private void record(Fixture f, String step) {
        long available = -ledgerService.balanceOf(f.poolAccountId());
        steps.add("%s -> available=%d".formatted(step, available));
    }

    private static String describe(SettlementSummary s) {
        return "pot %d, refund %s, claims settled %d, remaining %d, cycle %s".formatted(
                s.potKobo(),
                s.refund() == null ? "none" : s.refund().actualAmountKobo() + " of " + s.refund().expectedAmountKobo(),
                s.claimsSettled().size(), s.remainingKobo(), s.cycleStatus());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
