package com.theninjadev.ajoapi.payout;

import com.theninjadev.ajoapi.ledger.LedgerService;
import java.time.Instant;
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
        // TODO: implemented by hand
        throw new UnsupportedOperationException();
    }
}
