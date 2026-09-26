package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.round.CycleRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Read paths only — buyIn itself is not implemented yet. */
@SpringBootTest
@AutoConfigureMockMvc
class BuyInReadTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;
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

    private record Fixture(UUID roundId, TestUser admin, TestUser ada, TestUser eze) {}

    @Test
    void nonGroupMemberCannotListBuyIns() throws Exception {
        var f = setUp();
        var outsider = client.registerUser("Outsider");

        client.listBuyInsForRoundAndExpect(outsider, f.roundId(), 404);
    }

    @Test
    void roundWithNoBuyInsReturnsEmptyList() throws Exception {
        var f = setUp();

        assertThat(client.listBuyInsForRound(f.admin(), f.roundId())).isEmpty();
    }

    @Test
    void buyInForAnUnsettledExitIsNotFound() throws Exception {
        var f = setUp();
        var firstCycle = cycleRepository.findByRoundIdOrderByCycleNumberAsc(f.roundId()).getFirst();

        // Ada contributes without having collected, so the group owes her and her exit
        // stays PENDING_SETTLEMENT — an exit a buy-in could settle, but none has yet.
        client.contribute(f.ada(), firstCycle.getId(), AMOUNT, f.ada().id(), UUID.randomUUID().toString());
        var exit = client.requestExit(f.ada(), f.roundId());
        assertThat(exit.status()).isEqualTo(ExitStatus.PENDING_SETTLEMENT);

        // The detail proves this is the missing buy-in, not a missing exit.
        client.getBuyInForExitAndExpectDetail(f.admin(), exit.id(), 404, "Buy-in not found");
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

        client.activate(admin, roundId);

        return new Fixture(roundId, admin, ada, eze);
    }
}
