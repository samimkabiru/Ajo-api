package com.theninjadev.ajoapi.round;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The reported-status rule, without Spring: only SCHEDULED is ever shadowed, and only by date. */
class CycleTest {

    private static final LocalDate OPENS_ON = LocalDate.of(2026, 6, 1);

    private static Cycle cycle(CycleStatus status) {
        return Cycle.builder().status(status).opensOn(OPENS_ON).build();
    }

    @Test
    void scheduledReportsScheduledBeforeItsOpeningDate() {
        assertThat(cycle(CycleStatus.SCHEDULED).effectiveStatusAt(OPENS_ON.minusDays(1)))
                .isEqualTo(CycleStatus.SCHEDULED);
    }

    @Test
    void scheduledReportsOpenOnItsOpeningDate() {
        assertThat(cycle(CycleStatus.SCHEDULED).effectiveStatusAt(OPENS_ON)).isEqualTo(CycleStatus.OPEN);
    }

    @Test
    void scheduledReportsOpenAfterItsOpeningDate() {
        assertThat(cycle(CycleStatus.SCHEDULED).effectiveStatusAt(OPENS_ON.plusDays(1)))
                .isEqualTo(CycleStatus.OPEN);
    }

    @Test
    void realEventsReportThemselvesHoweverLongAgoTheCycleOpened() {
        LocalDate monthsLater = OPENS_ON.plusMonths(5);

        assertThat(cycle(CycleStatus.PAID).effectiveStatusAt(monthsLater)).isEqualTo(CycleStatus.PAID);
        assertThat(cycle(CycleStatus.VACANT).effectiveStatusAt(monthsLater)).isEqualTo(CycleStatus.VACANT);
        assertThat(cycle(CycleStatus.SETTLED).effectiveStatusAt(monthsLater)).isEqualTo(CycleStatus.SETTLED);
    }

    @Test
    void openStaysOpenOnAnyDate() {
        assertThat(cycle(CycleStatus.OPEN).effectiveStatusAt(OPENS_ON.minusDays(1))).isEqualTo(CycleStatus.OPEN);
        assertThat(cycle(CycleStatus.OPEN).effectiveStatusAt(OPENS_ON.plusMonths(1))).isEqualTo(CycleStatus.OPEN);
    }

    @Test
    void contributionsAreAcceptedFromTheOpeningDateOn() {
        Cycle cycle = cycle(CycleStatus.SCHEDULED);

        assertThat(cycle.isOpenForContributionsAt(OPENS_ON.minusDays(1))).isFalse();
        assertThat(cycle.isOpenForContributionsAt(OPENS_ON)).isTrue();
        assertThat(cycle.isOpenForContributionsAt(OPENS_ON.plusDays(1))).isTrue();
    }
}
