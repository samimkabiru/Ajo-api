package com.theninjadev.ajoapi.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

public class AdjustableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public AdjustableClock() {
        this(Instant.now(), ZoneId.of("UTC"));
    }

    public AdjustableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void advanceBy(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new AdjustableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
