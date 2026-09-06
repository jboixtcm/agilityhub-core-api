package com.agilityhub.core.shared.application;

import java.time.LocalDate;
import java.time.ZonedDateTime;

public interface ClubClock {
    LocalDate today(String clubId);
    ZonedDateTime now(String clubId);
}
