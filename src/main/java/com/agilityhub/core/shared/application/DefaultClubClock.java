package com.agilityhub.core.shared.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;

public final class DefaultClubClock implements ClubClock {
    private final Clock clock;
    private final TimeZoneProvider zones;

    public DefaultClubClock(Clock clock, TimeZoneProvider zones) {
        this.clock = clock;
        this.zones = zones;
    }

    @Override
    public LocalDate today(String clubId) { return now(clubId).toLocalDate(); }

    @Override
    public ZonedDateTime now(String clubId) { return clock.instant().atZone(zones.timeZone(clubId)); }
}
