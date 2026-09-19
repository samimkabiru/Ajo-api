package com.theninjadev.ajoapi.round;

import com.theninjadev.ajoapi.auth.AuthResponse;
import com.theninjadev.ajoapi.auth.RegisterRequest;
import com.theninjadev.ajoapi.group.CreateGroupRequest;
import com.theninjadev.ajoapi.group.GroupInviteSummary;
import com.theninjadev.ajoapi.group.GroupSummary;
import com.theninjadev.ajoapi.group.InviteMemberRequest;
import com.theninjadev.ajoapi.ledger.AccountType;
import com.theninjadev.ajoapi.ledger.LedgerAccount;
import com.theninjadev.ajoapi.ledger.LedgerAccountRepository;
import com.theninjadev.ajoapi.ledger.LedgerEntryRepository;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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

    @Autowired
    private CycleRepository cycleRepository;

    private record TestUser(UUID id, String phone, String accessToken) {}
    private record ActivatedRound(UUID roundId, TestUser admin, TestUser ada, TestUser eze) {}

    @Test
    void activationCreatesOneCyclePerParticipant() throws Exception {
        var activatedRound = setUpRound("08031110", LocalDate.of(2026, 3, 31));

        var detail = activate(activatedRound.admin, activatedRound.roundId);
        assertThat(detail.cycles()).hasSize(3);
        assertThat(detail.participants()).hasSize(3);
    }

    @Test
    void activationAssignsSequentialPositions() throws Exception {
        var activatedRound = setUpRound("08099812", LocalDate.of(2026, 3, 31));

        var detail = activate(activatedRound.admin, activatedRound.roundId);

        var positions = detail.participants().stream()
                .map(ParticipantSummary::payoutPosition)
                .sorted()
                .toList();

        assertThat(positions).containsExactly(1, 2, 3);
    }

    @Test
    void eachParticipantIsBeneficiaryOfExactlyOneCycle() throws Exception {
        var activatedRound = setUpRound("08032392", LocalDate.of(2026, 3, 31));

        var detail = activate(activatedRound.admin, activatedRound.roundId);

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
        var activatedRound = setUpRound("08023991", LocalDate.of(2026, 3, 31));

        var detail = activate(activatedRound.admin, activatedRound.roundId);

        var userIdByPosition = detail.participants().stream()
                .collect(Collectors.toMap(ParticipantSummary::payoutPosition, p -> p.user().id()));

        for (var cycle : detail.cycles()) {
            assertThat(cycle.beneficiary().id())
                    .isEqualTo(userIdByPosition.get(cycle.cycleNumber()));
        }
    }

    @Test
    void activationCreatesNoLedgerEntries() throws Exception {
        var activatedRound = setUpRound("08096308", LocalDate.of(2026, 3, 31));

        long before = ledgerEntryRepository.count();
        activate(activatedRound.admin, activatedRound.roundId);
        assertThat(ledgerEntryRepository.count()).isEqualTo(before);
    }

    @Test
    void roundPoolAccountExists() throws Exception {
        var activatedRound = setUpRound("08092381", LocalDate.of(2026, 3, 31));

        activate(activatedRound.admin, activatedRound.roundId);
        var account = ledgerAccountRepository.findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, activatedRound.roundId);

        assertThat(account).isPresent();
    }

    @Test
    void participantAccountPerParticipant() throws Exception {
        var activatedRound = setUpRound("08098273", LocalDate.of(2026, 3, 31));

        var detail = activate(activatedRound.admin, activatedRound.roundId);

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
        var activatedRound = setUpRound("08049899", LocalDate.of(2026, 3, 31));

        var detail = activate(activatedRound.admin, activatedRound.roundId);

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
        var activatedRound = setUpRound("08028282", LocalDate.of(2026, 3, 31));

        var detail = activate(activatedRound.admin, activatedRound.roundId);
        for (var cycle : detail.cycles()) {
            assertThat(cycle.opensOn()).isEqualTo(cycle.payoutOn().withDayOfMonth(1));
            assertThat(cycle.dueOn()).isEqualTo(cycle.payoutOn());
        }
    }

    @Test
    void nonAdminCannotActivateRound() throws Exception {
        var activatedRound = setUpRound("08022190", LocalDate.of(2026, 3, 31));

        activateAndExpect(activatedRound.ada, activatedRound.roundId, 403);
    }

    @Test
    void cannotActivateRoundWithLessThanTwoParticipants() throws Exception {
        var admin = registerUser("08098244691", "Alice");

        var groupId = createGroup(admin, "Alice's Ajo");

        var roundId = createRound(admin, groupId, 1000000L, LocalDate.of(2026, 3, 31));

        addParticipant(admin, roundId, admin);

        activateAndExpect(admin, roundId, 409);
    }

    @Test
    void firstPayoutDateCannotBeNullOnActivation() throws Exception {
        var activatedRound = setUpRound("08032340", null);

        activateAndExpect(activatedRound.admin, activatedRound.roundId, 409);
    }

    @Test
    void cannotActivateAlreadyActiveRound() throws Exception {
        var activatedRound = setUpRound("08028399", LocalDate.of(2026, 3, 31));

        activate(activatedRound.admin, activatedRound.roundId);
        activateAndExpect(activatedRound.admin, activatedRound.roundId, 409);
    }

    @Test
    void cannotActivateAlreadyCanceledRound() throws Exception {
        var activatedRound = setUpRound("08029990", LocalDate.of(2026, 3, 31));

        mockMvc.perform(post("/rounds/" + activatedRound.roundId + "/cancel")
                .header("Authorization", "Bearer " + activatedRound.admin.accessToken()))
                .andExpect(status().isOk());

        activateAndExpect(activatedRound.admin, activatedRound.roundId, 409);
    }

    @Test
    void failedActivationWritesNothing() throws Exception {
        var admin = registerUser("08031237001", "Alice");

        var groupId = createGroup(admin, "Alice's Ajo");

        var roundId = createRound(admin, groupId, 1000000L, LocalDate.of(2026, 3, 31));

        addParticipant(admin, roundId, admin);

        long entriesBefore = ledgerEntryRepository.count();

        mockMvc.perform(post("/rounds/" + roundId + "/activate")
                        .header("Authorization", "Bearer " + admin.accessToken()))
                .andExpect(status().isConflict());

        assertThat(cycleRepository.findByRoundIdOrderByCycleNumberAsc(roundId)).isEmpty();
        assertThat(ledgerEntryRepository.count()).isEqualTo(entriesBefore);
        assertThat(ledgerAccountRepository.findByAccountTypeAndOwnerId(AccountType.ROUND_POOL, roundId)).isEmpty();
    }

    private ActivatedRound setUpRound(String phonePrefix, LocalDate firstPayoutDate) throws Exception {
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

        return new ActivatedRound(roundId, admin, ada, eze);
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

    private void activateAndExpect(TestUser caller, UUID roundId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/rounds/" + roundId + "/activate")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().is(expectedStatus));
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
