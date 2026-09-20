package com.theninjadev.ajoapi.contribution;

import java.util.UUID;

public record PoolBalance(
        UUID roundId,
        long balanceKobo
) {}
