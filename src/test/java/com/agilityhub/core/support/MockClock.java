package com.agilityhub.core.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Mutable test time; zone views share the same instant. */
public final class MockClock extends Clock {

    private final AtomicReference<Instant> currentInstant;
    private final ZoneId zone;

    public MockClock(Instant instant) {
        this(new AtomicReference<>(Objects.requireNonNull(instant)), ZoneOffset.UTC);
    }

    private MockClock(AtomicReference<Instant> currentInstant, ZoneId zone) {
        this.currentInstant = currentInstant;
        this.zone = Objects.requireNonNull(zone);
    }

    public void setInstant(Instant instant) {
        currentInstant.set(Objects.requireNonNull(instant));
    }

    public void advance(Duration duration) {
        currentInstant.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public MockClock withZone(ZoneId zone) {
        return new MockClock(currentInstant, zone);
    }

    @Override
    public Instant instant() {
        return currentInstant.get();
    }
}
