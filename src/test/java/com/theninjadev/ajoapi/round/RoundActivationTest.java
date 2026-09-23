package com.theninjadev.ajoapi.round;

import com.theninjadev.ajoapi.ledger.AccountType;
import com.theninjadev.ajoapi.ledger.LedgerAccount;
import com.theninjadev.ajoapi.ledger.LedgerAccountRepository;
import com.theninjadev.ajoapi.ledger.LedgerEntryRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class RoundActivationTest extends AbstractIntegrationTest {

    @Autowired
    public LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    public LedgerEntryRepository ledgerEntryRepository;

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

    @Test
    void activationCreatesOneCyclePerParticipant() throws Exception {
        var preparedRound = setUpRound(LocalDate.of(2026, 3, 31));

        var detail = client.activate(preparedRound.admin, preparedRound.roundId);
        assertThat(detail.cycles()).hasSize(3);
        assertThat(detail.participants()).hasSize(3);
    }

    @Test
    void activationAssignsSequentialPositions() throws Exception {
        var preparedRound = setUpRound(LocalDate.of(2026, 3, 31));

        var detail = client.activate(preparedRound.admin, preparedRound.roundId);

        var positions = detail.participants().stream()
                .map(ParticipantSummary::payoutPosition)
                .sorted()
                .toList();

        assertThat(positions).containsExactly(1, 2, 3);
    }

    @Test
    void eachParticipantIsBeneficiaryOfExactlyOneCycle() throws Exception {
        var preparedRound = setUpRound(LocalDate.of(2026, 3, 31));

        var detail = client.activate(preparedRound.admin, preparedRound.roundId);

        var beneficiaryIds = detail.cycles().stream()
                .map(c -> c.beneficiary().id())
                .toList();

        var participantUserIds = detail.participants().stream()
                .map(p -> p.user().id())
                .toList();

        assertThat(beneficiaryIds).doesNotHaveDuplicates();
        assertThat(beneficiaryIds).containsExactlyInAnyOrderElementsOf(participantUserIds);

    }

    @Test
    void cycleNumberMatchesBeneficiaryPayoutPosition() throws Exception {
        var preparedRound = setUpRound(LocalDate.of(2026, 3, 31));

        var detail = client.activate(preparedRound.admin, preparedRound.roundId);

        var userIdByPosition = detail.participants().stream()
                .collect(Collectors.toMap(ParticipantSummary::payoutPosition, p -> p.user().id()));

        for (var cycle : detail.cycles()) {
            assertThat(cycle.beneficiary().id())
                    .isEqualTo(userIdByPosition.get(cycle.cycleNumber()));
        }
    }

    @Test
    void activationCreatesNoLedgerEntries() throws Exception {
        var preparedRound = setUpRound(LocalDate.of(2026, 3, 31));

        long before = ledgerEntryRepository.count();
        client.activate(preparedRound.admin, preparedRound.roundId);
        assertThat(ledgerEntryRepository.count()).isEqualTo(before);
    }

    @Test
    void roundPoolAccountExists() throws Exception {
        var preparedRound = setUpRound(LocalDate.of(2026, 3, 31));

        client.activate(preparedRound.admin, preparedRound.roundId);
        var account = ledgerAccountRepository.findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, preparedRound.roundId);

        assertThat(account).isPresent();
    }

    @Test
    void participantAccountPerParticipant() throws Exception {
        var preparedRound = setUpRound(LocalDate.of(2026, 3, 31));

        var detail = client.activate(preparedRound.admin, preparedRound.roundId);

        var participantRowIds = detail.participants().stream()
                .map(ParticipantSummary::id)
                .toList();

        List<LedgerAccount> accounts = new ArrayList<>();
        participantRowIds.forEach(p -> {
            var account = ledgerAccountRepository.findByAccountTypeAndOwnerId(AccountType.PARTICIPANT, p).orElse(null);
            if (account != null) {
                accounts.add(account);
            }
        });

        assertThat(accounts).hasSize(3);
    }

    @Test
    void payoutDatesClampToMonthEnd() throws Exception {
        var preparedRound = setUpRound(LocalDate.of(2026, 3, 31));

        var detail = client.activate(preparedRound.admin, preparedRound.roundId);

        var payoutDates = detail.cycles().stream()
                .sorted(Comparator.comparingInt(CycleSummary::cycleNumber))
                .map(CycleSummary::payoutOn)
                .toList();

        assertThat(payoutDates).containsExactly(
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 5, 31));
    }

    @Test
    void cycleOpensOnFirstOfPayoutMonth() throws Exception {
        var preparedRound = setUpRound(LocalDate.of(2026, 3, 31));

        var detail = client.activate(preparedRound.admin, preparedRound.roundId);
        for (var cycle : detail.cycles()) {
            assertThat(cycle.opensOn()).isEqualTo(cycle.payoutOn().withDayOfMonth(1));
            assertThat(cycle.dueOn()).isEqualTo(cycle.payoutOn());
        }
    }

    @Test
    void nonAdminCannotActivateRound() throws Exception {
        var preparedRound = setUpRound(LocalDate.of(2026, 3, 31));

        client.activateAndExpect(preparedRound.ada, preparedRound.roundId, 403);
    }

    @Test
    void cannotActivateRoundWithLessThanTwoParticipants() throws Exception {
        var admin = client.registerUser("Alice");

        var groupId = client.createGroup(admin, "Alice's Ajo");

        var roundId = client.createRound(admin, groupId, 1000000L, LocalDate.of(2026, 3, 31));

        client.addParticipant(admin, roundId, admin);

        client.activateAndExpect(admin, roundId, 409);
    }

    @Test
    void firstPayoutDateCannotBeNullOnActivation() throws Exception {
        var preparedRound = setUpRound(null);

        client.activateAndExpect(preparedRound.admin, preparedRound.roundId, 409);
    }

    @Test
    void cannotActivateAlreadyActiveRound() throws Exception {
        var preparedRound = setUpRound(LocalDate.of(2026, 3, 31));

        client.activate(preparedRound.admin, preparedRound.roundId);
        client.activateAndExpect(preparedRound.admin, preparedRound.roundId, 409);
    }

    @Test
    void cannotActivateAlreadyCanceledRound() throws Exception {
        var preparedRound = setUpRound(LocalDate.of(2026, 3, 31));

        mockMvc.perform(post("/rounds/" + preparedRound.roundId + "/cancel")
                .header("Authorization", "Bearer " + preparedRound.admin.accessToken()))
                .andExpect(status().isOk());

        client.activateAndExpect(preparedRound.admin, preparedRound.roundId, 409);
    }

    @Test
    void failedActivationWritesNothing() throws Exception {
        var admin = client.registerUser("Alice");

        var groupId = client.createGroup(admin, "Alice's Ajo");

        var roundId = client.createRound(admin, groupId, 1000000L, LocalDate.of(2026, 3, 31));

        client.addParticipant(admin, roundId, admin);

        long entriesBefore = ledgerEntryRepository.count();

        mockMvc.perform(post("/rounds/" + roundId + "/activate")
                        .header("Authorization", "Bearer " + admin.accessToken()))
                .andExpect(status().isConflict());

        assertThat(cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId)).isEmpty();
        assertThat(ledgerEntryRepository.count()).isEqualTo(entriesBefore);
        assertThat(ledgerAccountRepository.findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, roundId)).isEmpty();
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
