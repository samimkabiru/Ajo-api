package com.theninjadev.ajoapi.testsupport;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class AdjustableClockConfig {

    /** Microsecond precision, mirroring the production clock's tick — see AdjustableClock.instant(). */
    @Bean
    @Primary
    public Clock testClock() {
        return new AdjustableClock();
    }
}
