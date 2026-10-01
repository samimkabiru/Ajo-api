package com.theninjadev.ajoapi.config;

import com.theninjadev.ajoapi.testsupport.AdjustableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Postgres stores microseconds, so no clock the application uses may produce anything finer. */
class ClockConfigTest {

    @Test
    void productionClockNeverProducesSubMicrosecondInstants() {
        Clock clock = new ClockConfig().clock();

        for (int i = 0; i < 1_000; i++) {
            Instant instant = clock.instant();
            assertThat(instant.getNano() % 1_000).as("sample %d: %s", i, instant).isZero();
        }
    }

    @Test
    void adjustableTestClockMatchesProductionPrecision() {
        var clock = new AdjustableClock(Instant.parse("2026-03-31T10:00:00.123456789Z"), ZoneOffset.UTC);
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-03-31T10:00:00.123456Z"));

        clock.advanceBy(Duration.ofNanos(1_234_567));
        assertThat(clock.instant().getNano() % 1_000).isZero();
    }
}
