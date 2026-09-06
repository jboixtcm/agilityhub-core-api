package com.agilityhub.core.shared.application;

import java.time.ZoneId;

public interface TimeZoneProvider {
    ZoneId timeZone(String clubId);
}
