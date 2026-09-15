package com.theninjadev.ajoapi.ledger;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@AllArgsConstructor
public class LedgerService {
    private final LedgerAccountRepository ledgerAccountRepository;
    private final Clock clock;
    private final LedgerEntryRepository ledgerEntryRepository;

    @Transactional
    public UUID post(EntryType type, UUID referenceId, List<PostingLine> lines) {
        if (lines == null || lines.size() < 2)
            throw new UnbalancedPostingException("A posting requires at least two lines");

        lines.forEach(line -> {
            if (line.amountKobo() == 0)
                throw new UnbalancedPostingException("Posting line amount cannot be zero");
        });

        var total = lines.stream().mapToLong(PostingLine::amountKobo).sum();
        if (total != 0)
            throw new UnbalancedPostingException("Posting lines must sum to zero, got " + total);

        var accountIds = lines.stream().map(PostingLine::accountId).distinct().toList();
        if (ledgerAccountRepository.findAllById(accountIds).size() != accountIds.size())
            throw new LedgerAccountNotFoundException();

        UUID transactionId = UUID.randomUUID();
        Instant now = Instant.now(clock);
            var entries = lines.stream()
                    .map(line -> LedgerEntry
                    .builder()
                    .id(UUID.randomUUID())
                    .transactionId(transactionId)
                    .accountId(line.accountId())
                    .amountKobo(line.amountKobo())
                    .entryType(type)
                    .referenceId(referenceId)
                    .createdAt(now)
                    .build())
                    .toList();

            ledgerEntryRepository.saveAll(entries);

        return transactionId;
    }

    public long balanceOf(UUID accountId) {
        return ledgerEntryRepository.sumAmountKoboByAccountId(accountId);
    }
}
