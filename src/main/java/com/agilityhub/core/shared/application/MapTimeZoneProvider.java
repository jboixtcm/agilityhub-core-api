package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.ZoneId;
import java.util.Map;

public final class MapTimeZoneProvider implements TimeZoneProvider {
    private final Map<String, ZoneId> zones;

    public MapTimeZoneProvider(Map<String, ZoneId> zones) { this.zones = Map.copyOf(zones); }

    @Override
    public ZoneId timeZone(String clubId) {
        ZoneId zone = zones.get(clubId);
        if (zone == null) { throw new ApiException(ErrorCode.CLUB_NOT_FOUND); }
        return zone;
    }
}
