package com.theninjadev.ajoapi.payout;

import com.theninjadev.ajoapi.ledger.EntryType;
import com.theninjadev.ajoapi.ledger.LedgerAccounts;
import com.theninjadev.ajoapi.ledger.LedgerService;
import com.theninjadev.ajoapi.ledger.PostingLine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pays a sum of money across open shortfall claims, in the order given, writing a
 * ShortfallSettlement and a ledger posting for each. Shared by vacant-cycle settlement
 * and arrears netting.
 */
@Component
@AllArgsConstructor
public class ShortfallDistributor {

    private final LedgerService ledgerService;
    private final ShortfallClaimRepository shortfallClaimRepository;
    private final ShortfallSettlementRepository shortfallSettlementRepository;

    /**
     * Returns what is left undistributed and the claims that were paid.
     * MANDATORY: never opens its own transaction, only joins the caller's.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Distribution distribute(List<ShortfallClaim> claims, long available,
                                   UUID fundedByCycleId, UUID poolAccountId, Instant now) {
        long remaining = available;
        List<ShortfallClaim> settledClaims = new ArrayList<>();

        for (ShortfallClaim claim : claims) {
            if (remaining <= 0) break;

            long outstanding = claim.getAmountKobo() - claim.getSettledAmountKobo();
            long payment = Math.min(outstanding, remaining);

            UUID settlementId = UUID.randomUUID();
            UUID settlementTransactionId = ledgerService.post(
                    EntryType.SHORTFALL_SETTLEMENT,
                    settlementId,
                    List.of(
                            new PostingLine(LedgerAccounts.PLATFORM_CASH_ID, -payment),
                            new PostingLine(poolAccountId, payment)));

            shortfallSettlementRepository.save(ShortfallSettlement.builder()
                    .id(settlementId)
                    .claimId(claim.getId())
                    .fundedByCycleId(fundedByCycleId)
                    .amountKobo(payment)
                    .ledgerTransactionId(settlementTransactionId)
                    .createdAt(now)
                    .build());

            claim.settle(payment, now);
            shortfallClaimRepository.save(claim);
            settledClaims.add(claim);

            remaining -= payment;
        }

        return new Distribution(remaining, settledClaims);
    }
}
