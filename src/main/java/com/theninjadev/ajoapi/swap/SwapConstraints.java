package com.theninjadev.ajoapi.swap;

import jakarta.persistence.EntityManager;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class SwapConstraints {

    private final EntityManager entityManager;

    /**
     * Defers uq_cycles_round_beneficiary and uq_round_participants_position to end-of-transaction.
     * Postgres's SET CONSTRAINTS is a per-transaction setting — this has no effect unless called
     * from inside an active @Transactional method, before the intermediate writes that would
     * otherwise transiently violate one of the two constraints.
     */
    public void deferUniqueConstraints() {
        entityManager.createNativeQuery(
                "SET CONSTRAINTS uq_cycles_round_beneficiary, uq_round_participants_position DEFERRED")
                .executeUpdate();
    }
}
