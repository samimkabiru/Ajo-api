package com.theninjadev.ajoapi.contribution;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record PoolBalance(
        UUID roundId,
        @Schema(description = "The pool's signed ledger balance. The pool is a liability, so money held reads negative: a pool holding ₦30,000 is -3000000. Integer kobo: ₦10,000 is 1000000.", example = "-3000000")
        long balanceKobo
) {}
