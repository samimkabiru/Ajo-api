package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.round.Cycle;
import com.theninjadev.ajoapi.round.CycleRepository;
import com.theninjadev.ajoapi.round.ParticipantSummary;
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

/** Read paths only — settleVacantCycle and the pot helpers are not implemented yet. */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementReadTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CycleRepository cycleRepository;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper);
    }

    private record Fixture(UUID roundId, TestUser admin, TestUser ada, TestUser eze,
                           List<TestUser> members, List<Cycle> cycles,
                           Map<UUID, TestUser> userByParticipantId) {

        TestUser beneficiaryOf(Cycle cycle) {
            return userByParticipantId.get(cycle.getBeneficiaryId());
        }
    }

    // Access

    @Test
    void nonGroupMemberGetsNotFoundOnEveryRead() throws Exception {
        var f = setUp();
        var exit = requestExitOwedByTheGroup(f, f.ada());
        var outsider = client.registerUser("Outsider");

        client.listRefundsForRoundAndExpect(outsider, f.roundId(), 404);
        client.getRefundForExitAndExpectDetail(outsider, exit.id(), 404, "Not a member of this group");
        client.listOpenShortfallClaimsAndExpect(outsider, f.roundId(), 404);
        client.getVacantCycleStatusAndExpect(outsider, f.cycles().getFirst().getId(), 404);
    }

    // Refunds

    @Test
    void roundWithNoRefundsReturnsEmptyList() throws Exception {
        var f = setUp();

        assertThat(client.listRefundsForRound(f.admin(), f.roundId())).isEmpty();
    }

    @Test
    void refundForAnUnsettledExitIsNotFound() throws Exception {
        var f = setUp();
        var exit = requestExitOwedByTheGroup(f, f.ada());

        // The detail proves this is the missing refund, not a missing exit.
        client.getRefundForExitAndExpectDetail(f.admin(), exit.id(), 404, "Refund not found");
    }

    // Open shortfall claims

    @Test
    void openShortfallClaimsIncludeAClaimRaisedByAnUnderpaidPayout() throws Exception {
        var f = setUp();
        var cycle = f.cycles().getFirst();
        var beneficiary = f.beneficiaryOf(cycle);

        // Two of three contribute, so the payout comes up one share short.
        for (TestUser member : f.members().subList(0, 2)) {
            client.contribute(member, cycle.getId(), AMOUNT, member.id(), UUID.randomUUID().toString());
        }
        client.payout(f.admin(), cycle.getId(), beneficiary.id(), UUID.randomUUID().toString());

        var open = client.listOpenShortfallClaims(f.admin(), f.roundId());

        assertThat(open).hasSize(1);
        var claim = open.getFirst();
        assertThat(claim.cycleId()).isEqualTo(cycle.getId());
        assertThat(claim.participant().id()).isEqualTo(beneficiary.id());
        assertThat(claim.amountKobo()).isEqualTo(AMOUNT);
        assertThat(claim.settledAmountKobo()).isZero();
        assertThat(claim.outstandingKobo()).isEqualTo(AMOUNT);
        assertThat(claim.settledAt()).isNull();
    }

    // Helpers

    /** Contributes without collecting, then leaves — the exit stays PENDING_SETTLEMENT. */
    private ExitRequestSummary requestExitOwedByTheGroup(Fixture f, TestUser user) throws Exception {
        var firstCycle = f.cycles().getFirst();
        client.contribute(user, firstCycle.getId(), AMOUNT, user.id(), UUID.randomUUID().toString());
        var exit = client.requestExit(user, f.roundId());
        assertThat(exit.status()).isEqualTo(ExitStatus.PENDING_SETTLEMENT);
        return exit;
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

        var detail = client.activate(admin, roundId);

        Map<UUID, TestUser> usersById = members.stream()
                .collect(Collectors.toMap(TestUser::id, Function.identity()));
        Map<UUID, TestUser> userByParticipantId = detail.participants().stream()
                .collect(Collectors.toMap(ParticipantSummary::id, p -> usersById.get(p.user().id())));

        var cycles = cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId);

        return new Fixture(roundId, admin, ada, eze, members, cycles, userByParticipantId);
    }
}
