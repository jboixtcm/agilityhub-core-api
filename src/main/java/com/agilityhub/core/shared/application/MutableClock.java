package com.agilityhub.core.shared.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** A Clock that tests and the `local` profile can move (S15 R-15-22, WP-15-E `POST /test/clock`). Never used in staging or production. */
public abstract class MutableClock extends Clock {
    public abstract void setInstant(Instant instant);
    public abstract void advance(Duration duration);
}
