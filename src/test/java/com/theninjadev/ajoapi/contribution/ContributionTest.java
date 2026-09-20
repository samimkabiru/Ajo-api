package com.theninjadev.ajoapi.contribution;

import com.theninjadev.ajoapi.auth.AuthResponse;
import com.theninjadev.ajoapi.auth.RegisterRequest;
import com.theninjadev.ajoapi.group.CreateGroupRequest;
import com.theninjadev.ajoapi.group.GroupInviteSummary;
import com.theninjadev.ajoapi.group.GroupSummary;
import com.theninjadev.ajoapi.group.InviteMemberRequest;
import com.theninjadev.ajoapi.ledger.*;
import com.theninjadev.ajoapi.round.*;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
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

    @Autowired
    private CycleRepository cycleRepository;

    private record TestUser(UUID id, String phone, String accessToken) {}
    private record PreparedRound(UUID roundId, TestUser admin, TestUser ada, TestUser eze) {}

    // Structure tests
    @Test
    void oneContributionPostsTwoLedgerEntriesSummingToZero() throws Exception {
        var round = setUpRound("08198723", LocalDate.of(2026, 3, 31));
        activate(round.admin, round.roundId);

        var cycles = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId);

        var summary = contribute(round.ada, cycles.getFirst().getId(), 1000000L, round.ada.id(), UUID.randomUUID().toString());
        var entries = ledgerEntryRepository.findByTransactionId(summary.ledgerTransactionId());
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().mapToLong(LedgerEntry::getAmountKobo).sum()).isEqualTo(0L);
    }

    @Test
    void poolBalanceReflectsAllContributions() throws Exception {
        var round = setUpRound("08198724", LocalDate.of(2026, 3, 31));
        activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();
        var pool = ledgerAccountRepository
                .findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, round.roundId)
                .orElseThrow();

        contribute(round.admin, cycleId, 1000000L, round.admin.id(), UUID.randomUUID().toString());
        contribute(round.ada, cycleId, 1000000L, round.ada.id(), UUID.randomUUID().toString());
        contribute(round.eze, cycleId, 1000000L, round.eze.id(), UUID.randomUUID().toString());

        assertThat(ledgerEntryRepository.sumAmountKoboByAccountId(pool.getId())).isEqualTo(-3000000L);
    }

    @Test
    void platformCashIncreasesByContributionAmount() throws Exception {
        var round = setUpRound("08198725", LocalDate.of(2026, 3, 31));
        activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();

        long before = ledgerEntryRepository.sumAmountKoboByAccountId(LedgerAccounts.PLATFORM_CASH_ID);
        contribute(round.ada, cycleId, 1000000L, round.ada.id(), UUID.randomUUID().toString());

        assertThat(ledgerEntryRepository.sumAmountKoboByAccountId(LedgerAccounts.PLATFORM_CASH_ID))
                .isEqualTo(before + 1000000L);
    }

    // Idempotency tests
    @Test
    void sameIdempotencyKeyCreatesOneContributionAndOnePosting() throws Exception {
        var round = setUpRound("08198726", LocalDate.of(2026, 3, 31));
        activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();
        var key = UUID.randomUUID().toString();

        var first = contribute(round.ada, cycleId, 1000000L, round.ada.id(), key);
        var second = contribute(round.ada, cycleId, 1000000L, round.ada.id(), key);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.ledgerTransactionId()).isEqualTo(first.ledgerTransactionId());
        assertThat(contributionRepository.findByCycleId(cycleId)).hasSize(1);
        assertThat(ledgerEntryRepository.findByTransactionId(first.ledgerTransactionId())).hasSize(2);
    }

    @Test
    void differentKeysForSameCycleAndParticipantIsRejected() throws Exception {
        var round = setUpRound("08198727", LocalDate.of(2026, 3, 31));
        activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();

        contribute(round.ada, cycleId, 1000000L, round.ada.id(), UUID.randomUUID().toString());
        contributeAndExpect(round.ada, cycleId, 1000000L, round.ada.id(), UUID.randomUUID().toString(), 409);
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        var round = setUpRound("08198728", LocalDate.of(2026, 3, 31));
        activate(round.admin, round.roundId);

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
        var round = setUpRound("08198729", LocalDate.of(2026, 3, 31));
        activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();
        contributeAndExpect(round.ada, cycleId, 500000L, round.ada.id(), UUID.randomUUID().toString(), 400);
    }

    @Test
    void contributingBeforeCycleOpensIsRejected() throws Exception {
        var round = setUpRound("08198730", LocalDate.now().plusMonths(6).withDayOfMonth(28));
        activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();
        contributeAndExpect(round.ada, cycleId, 1000000L, round.ada.id(), UUID.randomUUID().toString(), 409);
    }

    // RoundNotActiveException is unreachable through the API for now — cycles only
    // exist after activation, and nothing completes a round yet. Testable once
    // rounds can reach COMPLETED.

    @Test
    void nonAdminCannotContributeForSomeoneElse() throws Exception {
        var round = setUpRound("08198733", LocalDate.of(2026, 3, 31));
        activate(round.admin, round.roundId);

        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();
        contributeAndExpect(round.ada, cycleId, 1000000L, round.eze.id(), UUID.randomUUID().toString(), 403);
    }

    @Test
    void adminCanRecordCashContributionForAnotherMember() throws Exception {
        var round = setUpRound("08198734", LocalDate.of(2026, 3, 31));
        activate(round.admin, round.roundId);

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
        var round = setUpRound("08198735", LocalDate.of(2026, 3, 31));
        activate(round.admin, round.roundId);

        var outsider = registerUser("08198736001", "Outsider");
        var cycleId = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst().getId();

        contributeAndExpect(outsider, cycleId, 1000000L, outsider.id(), UUID.randomUUID().toString(), 404);
    }

    // Behaviour
    @Test
    void firstContributionOpensTheCycle() throws Exception {
        var round = setUpRound("08198737", LocalDate.of(2026, 3, 31));
        activate(round.admin, round.roundId);

        var cycle = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst();
        assertThat(cycle.getStatus()).isEqualTo(CycleStatus.SCHEDULED);

        contribute(round.ada, cycle.getId(), 1000000L, round.ada.id(), UUID.randomUUID().toString());

        var reloaded = cycleRepository.findById(cycle.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CycleStatus.OPEN);
    }

    @Test
    void rejectedContributionWritesNothing() throws Exception {
        var round = setUpRound("08198738", LocalDate.of(2026, 3, 31));
        activate(round.admin, round.roundId);

        var cycle = cycleRepository.findByRoundIdOrderByCycleNumberAsc(round.roundId).getFirst();
        long entriesBefore = ledgerEntryRepository.count();

        contributeAndExpect(round.ada, cycle.getId(), 500000L, round.ada.id(),
                UUID.randomUUID().toString(), 400);

        assertThat(contributionRepository.findByCycleId(cycle.getId())).isEmpty();
        assertThat(ledgerEntryRepository.count()).isEqualTo(entriesBefore);

        var reloaded = cycleRepository.findById(cycle.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CycleStatus.SCHEDULED);
    }

    private ContributionSummary contribute(TestUser caller, UUID cycleId, long amountKobo,
                                           UUID targetUserId, String idempotencyKey) throws Exception {
        var request = new ContributeRequest(amountKobo, targetUserId, null);
        var result = mockMvc.perform(post("/cycles/" + cycleId + "/contributions")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ContributionSummary.class);
    }

    private void contributeAndExpect(TestUser caller, UUID cycleId, long amountKobo,
                                           UUID targetUserId, String idempotencyKey, int expectedStatus) throws Exception {
        var request = new ContributeRequest(amountKobo, targetUserId, null);
        mockMvc.perform(post("/cycles/" + cycleId + "/contributions")
                        .header("Authorization", "Bearer " + caller.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is(expectedStatus));
    }

    private PreparedRound setUpRound(String phonePrefix, LocalDate firstPayoutDate) throws Exception {
        var admin = registerUser(phonePrefix + "001", "Alice");
        var ada   = registerUser(phonePrefix + "002", "Ada");
        var eze   = registerUser(phonePrefix + "003", "Eze");

        var groupId = createGroup(admin, "Alice's Ajo");
        addToGroup(admin, groupId, ada);
        addToGroup(admin, groupId, eze);

        var roundId = createRound(admin, groupId, 1000000L, firstPayoutDate);
        addParticipant(admin, roundId, admin);
        addParticipant(admin, roundId, ada);
        addParticipant(admin, roundId, eze);

        return new PreparedRound(roundId, admin, ada, eze);
    }

    private void addParticipant(TestUser admin, UUID roundId, TestUser participant) throws Exception {
        var request = new AddParticipantRequest(participant.id());
        mockMvc.perform(post("/rounds/" + roundId + "/participants")
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private RoundDetail activate(TestUser caller, UUID roundId) throws Exception {
        var result = mockMvc.perform(post("/rounds/" + roundId + "/activate")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), RoundDetail.class);
    }

    private TestUser registerUser(String rawPhone, String fullName) throws Exception {
        var request = new RegisterRequest(rawPhone, "password123", fullName, null);
        var result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        AuthResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), AuthResponse.class);
        return new TestUser(response.user().id(), response.user().phone(), response.accessToken());
    }

    private UUID createGroup(TestUser owner, String name) throws Exception {
        var request = new CreateGroupRequest(name, "description");
        var result = mockMvc.perform(post("/groups")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        GroupSummary summary = objectMapper.readValue(
                result.getResponse().getContentAsString(), GroupSummary.class);
        return summary.id();
    }

    private void addToGroup(TestUser admin, UUID groupId, TestUser invitee) throws Exception {
        var request = new InviteMemberRequest(invitee.phone());
        var result = mockMvc.perform(post("/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        GroupInviteSummary invite = objectMapper.readValue(
                result.getResponse().getContentAsString(), GroupInviteSummary.class);

        mockMvc.perform(post("/groups/invites/" + invite.id() + "/accept")
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk());
    }

    private UUID createRound(TestUser admin, UUID groupId, long amountKobo, LocalDate firstPayoutDate) throws Exception {
        var request = new CreateRoundRequest(amountKobo, firstPayoutDate);
        var result = mockMvc.perform(post("/groups/" + groupId + "/rounds")
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        RoundSummary summary = objectMapper.readValue(
                result.getResponse().getContentAsString(), RoundSummary.class);
        return summary.id();
    }
}
