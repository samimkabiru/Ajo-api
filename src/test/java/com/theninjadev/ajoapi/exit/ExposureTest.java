package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.round.Cycle;
import com.theninjadev.ajoapi.round.CycleRepository;
import com.theninjadev.ajoapi.round.ParticipantSummary;
import com.theninjadev.ajoapi.round.RoundDetail;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.ApiTestClient;
import com.theninjadev.ajoapi.testsupport.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ExposureTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;          // ₦10,000 per member per cycle
    private static final long FULL_POT = 3 * AMOUNT;         // three participants
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CycleRepository cycleRepository;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper, userRepository);
    }

    private record Fixture(UUID roundId,
                           TestUser admin,
                           List<TestUser> members,
                           Map<UUID, UUID> participantIdByUserId,
                           Map<UUID, TestUser> userByParticipantId) {

        UUID participantIdOf(TestUser user) {
            return participantIdByUserId.get(user.id());
        }

        TestUser beneficiaryOf(Cycle cycle) {
            return userByParticipantId.get(cycle.getBeneficiaryId());
        }
    }

    @Test
    void contributingWithoutCollectingMakesExposureNegative() throws Exception {
        var f = setUp();
        var cycle = cycles(f).getFirst();
        var member = f.members().getFirst();

        client.contribute(member, cycle.getId(), AMOUNT, member.id(), newKey());

        var exposure = exposureOf(f.admin(), f.participantIdOf(member));

        assertThat(exposure.exposureKobo()).isEqualTo(-AMOUNT);
        assertThat(exposure.owedByGroup()).isTrue();
        assertThat(exposure.owesGroup()).isFalse();
    }

    @Test
    void collectingBeforeContributingTheRestMakesExposurePositive() throws Exception {
        var f = setUp();
        var cycle = cycles(f).getFirst();
        var beneficiary = f.beneficiaryOf(cycle);

        // Everyone pays into cycle 1, and its beneficiary collects the whole pot.
        contributeAll(f, cycle);
        client.payout(f.admin(), cycle.getId(), beneficiary.id(), newKey());

        var exposure = exposureOf(f.admin(), f.participantIdOf(beneficiary));

        // Collected the full pot, contributed only one month of it — the rest is owed.
        assertThat(exposure.exposureKobo()).isEqualTo(FULL_POT - AMOUNT);
        assertThat(exposure.owesGroup()).isTrue();
        assertThat(exposure.owedByGroup()).isFalse();
    }

    @Test
    void membersWhoHaveNotCollectedAreStillOwedAfterSomeoneElseIsPaid() throws Exception {
        var f = setUp();
        var cycle = cycles(f).getFirst();
        var beneficiary = f.beneficiaryOf(cycle);
        var other = f.members().stream()
                .filter(m -> !m.id().equals(beneficiary.id()))
                .findFirst()
                .orElseThrow();

        contributeAll(f, cycle);
        client.payout(f.admin(), cycle.getId(), beneficiary.id(), newKey());

        var exposure = exposureOf(f.admin(), f.participantIdOf(other));

        assertThat(exposure.exposureKobo()).isEqualTo(-AMOUNT);
        assertThat(exposure.owedByGroup()).isTrue();
    }

    @Test
    void everyoneEndsACompleteRoundAtZeroExposure() throws Exception {
        var f = setUp();

        for (Cycle cycle : cycles(f)) {
            contributeAll(f, cycle);
            client.payout(f.admin(), cycle.getId(), f.beneficiaryOf(cycle).id(), newKey());
        }

        for (TestUser member : f.members()) {
            var exposure = exposureOf(f.admin(), f.participantIdOf(member));

            assertThat(exposure.exposureKobo()).as("exposure for %s", member.id()).isZero();
            assertThat(exposure.owesGroup()).isFalse();
            assertThat(exposure.owedByGroup()).isFalse();
        }
    }

    @Test
    void anUntouchedParticipantHasZeroExposure() throws Exception {
        var f = setUp();
        var member = f.members().getFirst();

        var exposure = exposureOf(f.admin(), f.participantIdOf(member));

        assertThat(exposure.exposureKobo()).isZero();
    }

    // Helpers

    private ExposureSummary exposureOf(TestUser caller, UUID participantId) throws Exception {
        var result = mockMvc.perform(get("/participants/" + participantId + "/exposure")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ExposureSummary.class);
    }

    private List<Cycle> cycles(Fixture f) {
        return cycleRepository.findByRoundIdOrderByCycleNumberAsc(f.roundId());
    }

    private void contributeAll(Fixture f, Cycle cycle) throws Exception {
        for (TestUser member : f.members()) {
            client.contribute(member, cycle.getId(), AMOUNT, member.id(), newKey());
        }
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private Fixture setUp() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var eze = client.registerUser("Eze");
        var members = List.of(admin, ada, eze);

        var groupId = client.createGroup(admin, "Alice's Ajo");
        client.addToGroup(admin, groupId, ada);
        client.addToGroup(admin, groupId, eze);

        var roundId = client.createRound(admin, groupId, AMOUNT, PAST_START);
        for (TestUser member : members) {
            client.addParticipant(admin, roundId, member);
        }

        RoundDetail detail = client.activate(admin, roundId);

        Map<UUID, TestUser> usersById = members.stream()
                .collect(Collectors.toMap(TestUser::id, Function.identity()));

        Map<UUID, UUID> participantIdByUserId = detail.participants().stream()
                .collect(Collectors.toMap(p -> p.user().id(), ParticipantSummary::id));

        Map<UUID, TestUser> userByParticipantId = detail.participants().stream()
                .collect(Collectors.toMap(ParticipantSummary::id, p -> usersById.get(p.user().id())));

        return new Fixture(roundId, admin, members, participantIdByUserId, userByParticipantId);
    }
}