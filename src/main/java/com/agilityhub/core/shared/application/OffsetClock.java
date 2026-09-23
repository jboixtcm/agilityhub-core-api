package com.agilityhub.core.shared.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** System UTC time plus a movable offset: time keeps flowing after `POST /test/clock` sets or advances it. */
public final class OffsetClock extends MutableClock {
    private final Clock base;
    private final AtomicReference<Duration> offset;

    public OffsetClock(Clock base) { this(base, new AtomicReference<>(Duration.ZERO)); }
    private OffsetClock(Clock base, AtomicReference<Duration> offset) { this.base = base; this.offset = offset; }

    @Override public void setInstant(Instant instant) { offset.set(Duration.between(base.instant(), Objects.requireNonNull(instant))); }
    @Override public void advance(Duration duration) { offset.updateAndGet(current -> current.plus(duration)); }
    @Override public ZoneId getZone() { return base.getZone(); }
    @Override public Clock withZone(ZoneId zone) { return new OffsetClock(base.withZone(zone), offset); }
    @Override public Instant instant() { return base.instant().plus(offset.get()); }
}
