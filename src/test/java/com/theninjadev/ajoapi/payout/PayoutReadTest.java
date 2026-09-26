package com.theninjadev.ajoapi.payout;

import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.ApiTestClient;
import com.theninjadev.ajoapi.testsupport.TestUser;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PayoutReadTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper, userRepository);
    }

    private record ActivatedSetup(UUID roundId, UUID cycleId, TestUser admin, TestUser ada, TestUser eze) {}

    @Test
    void nonGroupMemberCannotListPayoutsForRound() throws Exception {
        var setup = setUpActivatedRound();
        var outsider = client.registerUser("Outsider");

        mockMvc.perform(get("/rounds/" + setup.roundId() + "/payouts")
                        .header("Authorization", "Bearer " + outsider.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void freshlyActivatedRoundHasNoPayouts() throws Exception {
        var setup = setUpActivatedRound();

        var result = mockMvc.perform(get("/rounds/" + setup.roundId() + "/payouts")
                        .header("Authorization", "Bearer " + setup.admin().accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        PayoutSummary[] payouts = objectMapper.readValue(
                result.getResponse().getContentAsString(), PayoutSummary[].class);

        assertThat(payouts).isEmpty();
    }

    @Test
    void unpaidCycleHasNoPayout() throws Exception {
        var setup = setUpActivatedRound();

        mockMvc.perform(get("/cycles/" + setup.cycleId() + "/payout")
                        .header("Authorization", "Bearer " + setup.admin().accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void freshlyActivatedRoundHasNoShortfallClaims() throws Exception {
        var setup = setUpActivatedRound();

        var result = mockMvc.perform(get("/rounds/" + setup.roundId() + "/shortfall-claims")
                        .header("Authorization", "Bearer " + setup.admin().accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        ShortfallClaimSummary[] claims = objectMapper.readValue(
                result.getResponse().getContentAsString(), ShortfallClaimSummary[].class);

        assertThat(claims).isEmpty();
    }

    private ActivatedSetup setUpActivatedRound() throws Exception {
        var admin = client.registerUser("Alice");
        var ada = client.registerUser("Ada");
        var eze = client.registerUser("Eze");

        var groupId = client.createGroup(admin, "Alice's Ajo");
        client.addToGroup(admin, groupId, ada);
        client.addToGroup(admin, groupId, eze);

        var roundId = client.createRound(admin, groupId, 1000000L, LocalDate.of(2026, 3, 31));
        client.addParticipant(admin, roundId, admin);
        client.addParticipant(admin, roundId, ada);
        client.addParticipant(admin, roundId, eze);

        var detail = client.activate(admin, roundId);
        return new ActivatedSetup(roundId, detail.cycles().get(0).id(), admin, ada, eze);
    }
}
