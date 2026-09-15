package com.theninjadev.ajoapi.ledger;

import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
public class LedgerServiceTest extends AbstractIntegrationTest {
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void balancedTwoLinePostingSucceeds() {
        var account1 = createAccount();
        var account2 = createAccount();

        var transactionId = ledgerService.post(EntryType.PAYOUT, null, List.of(
                new PostingLine(account1.getId(), 1000000L),
                new PostingLine(account2.getId(), -1000000L)
        ));

        var entries = ledgerEntryRepository.findByTransactionId(transactionId);
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(LedgerEntry::getAmountKobo)
                .containsExactlyInAnyOrder(1000000L, -1000000L);
        assertThat(ledgerService.balanceOf(account1.getId())).isEqualTo(1000000L);
        assertThat(ledgerService.balanceOf(account2.getId())).isEqualTo(-1000000L);
    }

    @Test
    void unbalancedPostingThrowsAndPersistsNothing() {
        var account1 = createAccount();
        var account2 = createAccount();
        long before = ledgerEntryRepository.count();

        assertThrows(UnbalancedPostingException.class, () -> ledgerService.post(EntryType.PAYOUT, null, List.of(
                new PostingLine(account1.getId(), 1000L),
                new PostingLine(account2.getId(), -1000000L)
        )));

        assertThat(ledgerEntryRepository.count()).isEqualTo(before);
    }

    @Test
    void zeroAmountLineIsRejected() {
        var account1 = createAccount();
        var account2 = createAccount();

        assertThrows(UnbalancedPostingException.class, () -> ledgerService.post(EntryType.PAYOUT, null, List.of(
                new PostingLine(account1.getId(), 0L),
                new PostingLine(account2.getId(), 0L)
        )));
    }

    @Test
    void singleLinePostingIsRejected() {
        var account1 = createAccount();

        assertThrows(UnbalancedPostingException.class, () -> ledgerService.post(EntryType.PAYOUT, null, List.of(
                new PostingLine(account1.getId(), 1000L)
        )));
    }

    @Test
    void postingToUnknownAccountIsRejected() {
        assertThrows(LedgerAccountNotFoundException.class, () -> ledgerService.post(EntryType.PAYOUT, null, List.of(
                new PostingLine(UUID.randomUUID(), 1000L),
                new PostingLine(UUID.randomUUID(), -1000L)
        )));
    }

    @Test
    void balanceOfUntouchedAccountIsZero() {
        var account1 = createAccount();

        assertThat(ledgerService.balanceOf(account1.getId())).isEqualTo(0L);
    }

    @Test
    void balanceOfReflectsSumOfEntries() {
        var account1 = createAccount();
        var account2 = createAccount();

        ledgerService.post(EntryType.PAYOUT, null, List.of(
                new PostingLine(account1.getId(), 1000L),
                new PostingLine(account1.getId(), 1000L),
                new PostingLine(account1.getId(), 1000L),
                new PostingLine(account2.getId(), -3000L)
        ));

        assertThat(ledgerService.balanceOf(account1.getId())).isEqualTo(3000L);
        assertThat(ledgerService.balanceOf(account2.getId())).isEqualTo(-3000L);
    }

    @Test
    void balancedThreeLinePostingSucceeds() {
        var account1 = createAccount();
        var account2 = createAccount();
        var account3 = createAccount();

        var transactionId = ledgerService.post(EntryType.PAYOUT, null, List.of(
                new PostingLine(account1.getId(), 1000000L),
                new PostingLine(account2.getId(), 1000000L),
                new PostingLine(account3.getId(), -2000000L)
        ));

        var entries = ledgerEntryRepository.findByTransactionId(transactionId);
        assertThat(entries.stream().mapToLong(LedgerEntry::getAmountKobo).sum()).isEqualTo(0L);
    }

    private LedgerAccount createAccount(AccountType type, UUID ownerId) {
        return ledgerAccountRepository.save(
                LedgerAccount.builder()
                        .id(UUID.randomUUID())
                        .accountType(type)
                        .ownerId(ownerId)
                        .currency("NGN")
                        .createdAt(Instant.now())
                        .build());
    }

    private LedgerAccount createAccount() {
        return createAccount(AccountType.ROUND_POOL, UUID.randomUUID());
    }
}
