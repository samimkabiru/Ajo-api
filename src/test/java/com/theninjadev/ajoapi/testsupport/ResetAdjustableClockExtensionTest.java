package com.theninjadev.ajoapi.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** A jump in one test must not survive into the next: the extension puts the clock back. */
@SpringBootTest
@Import(AdjustableClockConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResetAdjustableClockExtensionTest extends AbstractIntegrationTest {

    @Autowired private Clock clock;

    @Test
    @Order(1)
    void aTestJumpsTheClockAYearAhead() {
        ((AdjustableClock) clock).advanceBy(Duration.ofDays(365));

        assertThat(clock.instant()).isAfter(Instant.now().plus(Duration.ofDays(364)));
    }

    @Test
    @Order(2)
    void theNextTestFindsItBackAtThePresent() {
        assertThat(Duration.between(clock.instant(), Instant.now()).abs())
                .isLessThan(Duration.ofMinutes(5));
    }
}
