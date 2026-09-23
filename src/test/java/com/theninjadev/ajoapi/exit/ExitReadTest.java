package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.round.ParticipantStatus;
import com.theninjadev.ajoapi.round.ParticipantSummary;
import com.theninjadev.ajoapi.round.RoundParticipantRepository;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Read and cancel paths only. requestExit is not implemented yet, so open exits are
 * seeded straight through the repositories — the state requestExit will produce.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExitReadTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;
    private static final LocalDate PAST_START = LocalDate.of(2026, 3, 31);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ExitRequestRepository exitRequestRepository;
    @Autowired private RoundParticipantRepository roundParticipantRepository;
    @Autowired private Clock clock;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper);
    }

    private record Fixture(UUID roundId, TestUser admin, TestUser ada, TestUser eze,
                           Map<UUID, UUID> participantIdByUserId) {

        UUID participantIdOf(TestUser user) {
            return participantIdByUserId.get(user.id());
        }
    }

    // Reads

    @Test
    void nonGroupMemberCannotListExitsForRound() throws Exception {
        var f = setUp();
        var outsider = client.registerUser("Outsider");

        client.listExitsForRoundAndExpect(outsider, f.roundId(), 404);
    }

    @Test
    void roundWithNoExitsReturnsEmptyList() throws Exception {
        var f = setUp();

        assertThat(client.listExitsForRound(f.admin(), f.roundId())).isEmpty();
    }

    @Test
    void myExitWithNoOpenExitIsNotFound() throws Exception {
        var f = setUp();

        client.getMyExitAndExpect(f.ada(), f.roundId(), 404);
    }

    // Cancel

    @Test
    void cancellingAnUnknownExitIsNotFound() throws Exception {
        var f = setUp();

        client.cancelExitAndExpect(f.ada(), UUID.randomUUID(), 404);
    }

    @Test
    void onlyTheExitingParticipantCanCancel() throws Exception {
        var f = setUp();
        var exit = seedOpenExit(f, f.ada());

        client.cancelExitAndExpect(f.admin(), exit.getId(), 403);

        assertThat(exitStatus(exit.getId())).isEqualTo(ExitStatus.PENDING_SETTLEMENT);
        assertThat(participantStatus(f.participantIdOf(f.ada()))).isEqualTo(ParticipantStatus.PENDING_EXIT);
    }

    @Test
    void cancelSetsExitCancelledAndParticipantActive() throws Exception {
        var f = setUp();
        var exit = seedOpenExit(f, f.ada());

        var cancelled = client.cancelExit(f.ada(), exit.getId());

        assertThat(cancelled.status()).isEqualTo(ExitStatus.CANCELLED);
        assertThat(cancelled.completedAt()).isNotNull();
        assertThat(cancelled.participant().id()).isEqualTo(f.ada().id());

        assertThat(exitStatus(exit.getId())).isEqualTo(ExitStatus.CANCELLED);
        assertThat(participantStatus(f.participantIdOf(f.ada()))).isEqualTo(ParticipantStatus.ACTIVE);
    }

    @Test
    void aCancelledExitCannotBeCancelledAgain() throws Exception {
        var f = setUp();
        var exit = seedOpenExit(f, f.ada());
        client.cancelExit(f.ada(), exit.getId());

        client.cancelExitAndExpect(f.ada(), exit.getId(), 409);
    }

    @Test
    void aCancelledExitDoesNotBlockANewOpenExit() throws Exception {
        // uq_exit_requests_open is partial: only PENDING_SETTLEMENT rows compete.
        var f = setUp();
        var first = seedOpenExit(f, f.ada());
        client.cancelExit(f.ada(), first.getId());

        var second = seedOpenExit(f, f.ada());

        assertThat(client.listExitsForRound(f.admin(), f.roundId()))
                .extracting(ExitRequestSummary::id, ExitRequestSummary::status)
                .containsExactlyInAnyOrder(
                        tuple(first.getId(), ExitStatus.CANCELLED),
                        tuple(second.getId(), ExitStatus.PENDING_SETTLEMENT));
    }

    // Helpers

    /** The state requestExit leaves behind for a member who owes the group. */
    private ExitRequest seedOpenExit(Fixture f, TestUser user) {
        var participant = roundParticipantRepository.findById(f.participantIdOf(user)).orElseThrow();
        participant.markPendingExit();
        roundParticipantRepository.save(participant);

        return exitRequestRepository.save(ExitRequest.builder()
                .id(UUID.randomUUID())
                .roundId(f.roundId())
                .participantId(participant.getId())
                .exposureAtRequest(AMOUNT)
                .status(ExitStatus.PENDING_SETTLEMENT)
                .requestedAt(Instant.now(clock))
                .build());
    }

    private ExitStatus exitStatus(UUID exitId) {
        return exitRequestRepository.findById(exitId).orElseThrow().getStatus();
    }

    private ParticipantStatus participantStatus(UUID participantId) {
        return roundParticipantRepository.findById(participantId).orElseThrow().getStatus();
    }

    private Fixture setUp() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var eze = client.registerUser("Eze");

        var groupId = client.createGroup(admin, "Alice's Ajo");
        client.addToGroup(admin, groupId, ada);
        client.addToGroup(admin, groupId, eze);

        var roundId = client.createRound(admin, groupId, AMOUNT, PAST_START);
        client.addParticipant(admin, roundId, admin);
        client.addParticipant(admin, roundId, ada);
        client.addParticipant(admin, roundId, eze);

        var detail = client.activate(admin, roundId);
        Map<UUID, UUID> participantIdByUserId = detail.participants().stream()
                .collect(Collectors.toMap(p -> p.user().id(), ParticipantSummary::id));

        return new Fixture(roundId, admin, ada, eze, participantIdByUserId);
    }
}
