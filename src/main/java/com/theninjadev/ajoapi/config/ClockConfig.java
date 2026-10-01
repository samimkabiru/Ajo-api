package com.theninjadev.ajoapi.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /**
     * Ticks in whole microseconds, the precision Postgres TIMESTAMPTZ stores. Without this, a
     * POST response built from a just-saved entity carries nanoseconds that every later read
     * of the same row has lost, and the two disagree.
     */
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1000));
    }
}
