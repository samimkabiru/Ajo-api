package com.theninjadev.ajoapi.round;

import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.LocalDate;
import java.util.UUID;

public record CycleSummary(
        UUID id,
        int cycleNumber,
        UserSummary beneficiary,
        LocalDate opensOn,
        LocalDate dueOn,
        LocalDate payoutOn,
        CycleStatus status
) {}
