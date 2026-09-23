package com.theninjadev.ajoapi.contribution;

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
class ContributionReadTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper);
    }

    private record ActivatedSetup(UUID roundId, UUID cycleId, TestUser admin, TestUser ada, TestUser eze) {}

    @Test
    void nonGroupMemberCannotListContributionsForCycle() throws Exception {
        var setup = setUpActivatedRound();
        var outsider = client.registerUser("Outsider");

        mockMvc.perform(get("/cycles/" + setup.cycleId() + "/contributions")
                        .header("Authorization", "Bearer " + outsider.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void freshlyActivatedCycleHasNoContributions() throws Exception {
        var setup = setUpActivatedRound();

        var result = mockMvc.perform(get("/cycles/" + setup.cycleId() + "/contributions")
                        .header("Authorization", "Bearer " + setup.admin().accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        ContributionSummary[] contributions = objectMapper.readValue(
                result.getResponse().getContentAsString(), ContributionSummary[].class);

        assertThat(contributions).isEmpty();
    }

    @Test
    void poolBalanceIsZeroOnFreshlyActivatedRound() throws Exception {
        var setup = setUpActivatedRound();

        var result = mockMvc.perform(get("/rounds/" + setup.roundId() + "/pool-balance")
                        .header("Authorization", "Bearer " + setup.admin().accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        PoolBalance balance = objectMapper.readValue(
                result.getResponse().getContentAsString(), PoolBalance.class);

        assertThat(balance.balanceKobo()).isZero();
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
