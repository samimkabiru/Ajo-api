package com.theninjadev.ajoapi.round;

import com.theninjadev.ajoapi.auth.AuthResponse;
import com.theninjadev.ajoapi.auth.LoginRequest;
import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.contribution.ContributeRequest;
import com.theninjadev.ajoapi.payout.PayoutMethod;
import com.theninjadev.ajoapi.payout.PayoutRequest;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.AdjustableClock;
import com.theninjadev.ajoapi.testsupport.AdjustableClockConfig;
import com.theninjadev.ajoapi.testsupport.ApiTestClient;
import com.theninjadev.ajoapi.testsupport.TestUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A cycle reports OPEN from its opening date, whether or not anyone has contributed, while the
 * stored column keeps meaning "has anyone contributed yet". Driven by the adjustable clock, which
 * only ever moves forward here: every round is set up with its dates ahead of the current clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdjustableClockConfig.class)
class CycleEffectiveStatusTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;          // ₦10,000
    private static final String PASSWORD = "password123";   // ApiTestClient's registration password
    private static final String NOT_OPEN = "This cycle is not yet open for contributions";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper, userRepository);
    }

    @Test
    void theDayBeforeOpeningItReportsScheduledAndRefusesContributions() throws Exception {
        var f = activeRound();
        advanceTo(f.cycle1().opensOn().minusDays(1));
        String token = login(f.admin());

        cycleStatus(token, f, 0).andExpect(jsonPath("$.cycles[0].status").value("SCHEDULED"));

        contribute(token, f.cycle1().id(), f.admin().id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(NOT_OPEN));
    }

    @Test
    void onTheOpeningDayItReportsOpenWithoutWritingAndAcceptsContributions() throws Exception {
        var f = activeRound();
        advanceTo(f.cycle1().opensOn());
        String token = login(f.admin());

        cycleStatus(token, f, 0).andExpect(jsonPath("$.cycles[0].status").value("OPEN"));
        assertThat(storedStatus(f.cycle1().id()))
                .as("the GET reported OPEN but must not have written it")
                .isEqualTo("SCHEDULED");

        contribute(token, f.cycle1().id(), f.admin().id()).andExpect(status().isCreated());
    }

    @Test
    void theDayAfterOpeningItStillReportsOpenWithNoContributions() throws Exception {
        var f = activeRound();
        advanceTo(f.cycle1().opensOn().plusDays(1));
        String token = login(f.admin());

        cycleStatus(token, f, 0).andExpect(jsonPath("$.cycles[0].status").value("OPEN"));
        assertThat(storedStatus(f.cycle1().id())).isEqualTo("SCHEDULED");
    }

    @Test
    void aPaidCycleReportsPaidMonthsAfterItOpened() throws Exception {
        var f = activeRound();
        advanceTo(f.cycle1().payoutOn());
        String token = login(f.admin());

        contribute(token, f.cycle1().id(), f.admin().id()).andExpect(status().isCreated());
        contribute(token, f.cycle1().id(), f.ada().id()).andExpect(status().isCreated());
        mockMvc.perform(post("/cycles/" + f.cycle1().id() + "/payout")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PayoutRequest(PayoutMethod.ONLINE, f.cycle1().beneficiary().id()))))
                .andExpect(status().isCreated());

        advanceTo(f.cycle1().payoutOn().plusMonths(4));
        token = login(f.admin());

        cycleStatus(token, f, 0)
                .andExpect(jsonPath("$.cycles[0].status").value("PAID"))
                // Cycle 2 has opened too, untouched: the shadow applies to it, not to cycle 1.
                .andExpect(jsonPath("$.cycles[1].status").value("OPEN"));
        assertThat(storedStatus(f.cycle1().id())).isEqualTo("PAID");
    }

    // ------------------------------------------------------------------
    // Fixtures and helpers
    // ------------------------------------------------------------------

    private record Fixture(UUID roundId, TestUser admin, TestUser ada, CycleSummary cycle1) {}

    /** Two members, activated with every cycle at least a month in the future. */
    private Fixture activeRound() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var groupId = client.createGroup(admin, "Alice's Ajo");
        client.addToGroup(admin, groupId, ada);

        LocalDate firstPayout = LocalDate.now(clock).plusMonths(2).withDayOfMonth(28);
        var roundId = client.createRound(admin, groupId, AMOUNT, firstPayout);
        client.addParticipant(admin, roundId, admin);
        client.addParticipant(admin, roundId, ada);
        RoundDetail detail = client.activate(admin, roundId);

        CycleSummary cycle1 = detail.cycles().getFirst();
        assertThat(cycle1.opensOn()).isAfter(LocalDate.now(clock));
        return new Fixture(roundId, admin, ada, cycle1);
    }

    /** Moves the shared clock forward to midday UTC on {@code date}. Never backwards. */
    private void advanceTo(LocalDate date) {
        Instant target = date.atTime(12, 0).toInstant(ZoneOffset.UTC);
        Duration step = Duration.between(clock.instant(), target);
        assertThat(step).as("the clock only moves forward").isPositive();
        ((AdjustableClock) clock).advanceBy(step);
        assertThat(LocalDate.now(clock)).isEqualTo(date);
    }

    /** Tokens issued before a clock jump have expired. */
    private String login(TestUser user) throws Exception {
        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.phone(), PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    private ResultActions cycleStatus(String token, Fixture f, int cycleIndex) throws Exception {
        return mockMvc.perform(get("/rounds/" + f.roundId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycles[" + cycleIndex + "].id").value(cycleIdAt(f, cycleIndex)));
    }

    private String cycleIdAt(Fixture f, int cycleIndex) {
        return jdbc.queryForObject("SELECT id::text FROM cycles WHERE round_id = ? AND cycle_number = ?",
                String.class, f.roundId(), cycleIndex + 1);
    }

    /** The admin pays on a member's behalf, which the contribution endpoint allows. */
    private ResultActions contribute(String token, UUID cycleId, UUID memberId) throws Exception {
        return mockMvc.perform(post("/cycles/" + cycleId + "/contributions")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ContributeRequest(AMOUNT, memberId, null))));
    }

    private String storedStatus(UUID cycleId) {
        return jdbc.queryForObject("SELECT status FROM cycles WHERE id = ?", String.class, cycleId);
    }
}
