package com.theninjadev.ajoapi.contribution;

import com.theninjadev.ajoapi.ledger.*;
import com.theninjadev.ajoapi.round.*;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.ApiTestClient;
import com.theninjadev.ajoapi.testsupport.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class ContributionTest extends AbstractIntegrationTest {

    @Autowired
    public LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    public LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    public ContributionRepository contributionRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper);
    }

    @Autowired
    private CycleRepository cycleRepository;

    private record PreparedRound(UUID roundId, TestUser admin, TestUser ada, TestUser eze) {}

    // Structure tests
    @Test
    void oneContributionPostsTwoLedgerEntriesSummingToZero() throws Exception {
        var round = setUpRound(LocalDate.of(2026, 3, 31));
        client.activate(round.admin, round.roundId);

        var cycles = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId);

        var summary = client.contribute(round.ada, cycles.getFirst().getId(), 1000000L, round.ada.id(), UUID.randomUUID().toString());
        var entries = ledgerEntryRepository.findByTransactionId(summary.ledgerTransactionId());
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().mapToLong(LedgerEntry::getAmountKobo).sum()).isEqualTo(0L);
    }

    @Test
    void poolBalanceReflectsAllContributions() throws Exception {
        var round = setUpRound(LocalDate.of(2026, 3, 31));
        client.activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();
        var pool = ledgerAccountRepository
                .findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, round.roundId)
                .orElseThrow();

        client.contribute(round.admin, cycleId, 1000000L, round.admin.id(), UUID.randomUUID().toString());
        client.contribute(round.ada, cycleId, 1000000L, round.ada.id(), UUID.randomUUID().toString());
        client.contribute(round.eze, cycleId, 1000000L, round.eze.id(), UUID.randomUUID().toString());

        assertThat(ledgerEntryRepository.sumAmountKoboByAccountId(pool.getId())).isEqualTo(-3000000L);
    }

    @Test
    void platformCashIncreasesByContributionAmount() throws Exception {
        var round = setUpRound(LocalDate.of(2026, 3, 31));
        client.activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();

        long before = ledgerEntryRepository.sumAmountKoboByAccountId(LedgerAccounts.PLATFORM_CASH_ID);
        client.contribute(round.ada, cycleId, 1000000L, round.ada.id(), UUID.randomUUID().toString());

        assertThat(ledgerEntryRepository.sumAmountKoboByAccountId(LedgerAccounts.PLATFORM_CASH_ID))
                .isEqualTo(before + 1000000L);
    }

    // Idempotency tests
    @Test
    void sameIdempotencyKeyCreatesOneContributionAndOnePosting() throws Exception {
        var round = setUpRound(LocalDate.of(2026, 3, 31));
        client.activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();
        var key = UUID.randomUUID().toString();

        var first = client.contribute(round.ada, cycleId, 1000000L, round.ada.id(), key);
        var second = client.contribute(round.ada, cycleId, 1000000L, round.ada.id(), key);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.ledgerTransactionId()).isEqualTo(first.ledgerTransactionId());
        assertThat(contributionRepository.findByCycleId(cycleId)).hasSize(1);
        assertThat(ledgerEntryRepository.findByTransactionId(first.ledgerTransactionId())).hasSize(2);
    }

    @Test
    void differentKeysForSameCycleAndParticipantIsRejected() throws Exception {
        var round = setUpRound(LocalDate.of(2026, 3, 31));
        client.activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();

        client.contribute(round.ada, cycleId, 1000000L, round.ada.id(), UUID.randomUUID().toString());
        client.contributeAndExpect(round.ada, cycleId, 1000000L, round.ada.id(), UUID.randomUUID().toString(), 409);
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        var round = setUpRound(LocalDate.of(2026, 3, 31));
        client.activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();
        var request = new ContributeRequest(1000000L, round.ada.id(), null);

        mockMvc.perform(post("/cycles/" + cycleId + "/contributions")
                        .header("Authorization", "Bearer " + round.ada.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // Validation
    @Test
    void wrongAmountIsRejected() throws Exception {
        var round = setUpRound(LocalDate.of(2026, 3, 31));
        client.activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();
        client.contributeAndExpect(round.ada, cycleId, 500000L, round.ada.id(), UUID.randomUUID().toString(), 400);
    }

    @Test
    void contributingBeforeCycleOpensIsRejected() throws Exception {
        var round = setUpRound(LocalDate.now().plusMonths(6).withDayOfMonth(28));
        client.activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();
        client.contributeAndExpect(round.ada, cycleId, 1000000L, round.ada.id(), UUID.randomUUID().toString(), 409);
    }

    // RoundNotActiveException is unreachable through the API for now — cycles only
    // exist after activation, and nothing completes a round yet. Testable once
    // rounds can reach COMPLETED.

    @Test
    void nonAdminCannotContributeForSomeoneElse() throws Exception {
        var round = setUpRound(LocalDate.of(2026, 3, 31));
        client.activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();
        client.contributeAndExpect(round.ada, cycleId, 1000000L, round.eze.id(), UUID.randomUUID().toString(), 403);
    }

    @Test
    void adminCanRecordCashContributionForAnotherMember() throws Exception {
        var round = setUpRound(LocalDate.of(2026, 3, 31));
        client.activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();
        var request = new ContributeRequest(1000000L, round.ada.id(), ContributionMethod.CASH);

        var result = mockMvc.perform(post("/cycles/" + cycleId + "/contributions")
                        .header("Authorization", "Bearer " + round.admin.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        var summary = objectMapper.readValue(
                result.getResponse().getContentAsString(), ContributionSummary.class);

        assertThat(summary.recordedBy()).isEqualTo(round.admin.id());
        assertThat(summary.participant().id()).isEqualTo(round.ada.id());
        assertThat(summary.method()).isEqualTo(ContributionMethod.CASH);
    }

    @Test
    void nonGroupMemberCannotContribute() throws Exception {
        var round = setUpRound(LocalDate.of(2026, 3, 31));
        client.activate(round.admin, round.roundId);

        var outsider = client.registerUser("Outsider");
        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();

        client.contributeAndExpect(outsider, cycleId, 1000000L, outsider.id(), UUID.randomUUID().toString(), 404);
    }

    // Behaviour
    @Test
    void firstContributionOpensTheCycle() throws Exception {
        var round = setUpRound(LocalDate.of(2026, 3, 31));
        client.activate(round.admin, round.roundId);

        var cycle = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst();
        assertThat(cycle.getStatus()).isEqualTo(CycleStatus.SCHEDULED);

        client.contribute(round.ada, cycle.getId(), 1000000L, round.ada.id(), UUID.randomUUID().toString());

        var reloaded = cycleRepository.findById(cycle.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CycleStatus.OPEN);
    }

    @Test
    void rejectedContributionWritesNothing() throws Exception {
        var round = setUpRound(LocalDate.of(2026, 3, 31));
        client.activate(round.admin, round.roundId);

        var cycle = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst();
        long entriesBefore = ledgerEntryRepository.count();

        client.contributeAndExpect(round.ada, cycle.getId(), 500000L, round.ada.id(),
                UUID.randomUUID().toString(), 400);

        assertThat(contributionRepository.findByCycleId(cycle.getId())).isEmpty();
        assertThat(ledgerEntryRepository.count()).isEqualTo(entriesBefore);

        var reloaded = cycleRepository.findById(cycle.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CycleStatus.SCHEDULED);
    }

    private PreparedRound setUpRound(LocalDate firstPayoutDate) throws Exception {
        var admin = client.registerUser("Alice");
        var ada   = client.registerUser("Ada");
        var eze   = client.registerUser("Eze");

        var groupId = client.createGroup(admin, "Alice's Ajo");
        client.addToGroup(admin, groupId, ada);
        client.addToGroup(admin, groupId, eze);

        var roundId = client.createRound(admin, groupId, 1000000L, firstPayoutDate);
        client.addParticipant(admin, roundId, admin);
        client.addParticipant(admin, roundId, ada);
        client.addParticipant(admin, roundId, eze);

        return new PreparedRound(roundId, admin, ada, eze);
    }
}
