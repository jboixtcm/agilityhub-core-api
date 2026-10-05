package com.agilityhub.core.clubs.census.application.ports;

import java.time.LocalDate;

/** A synchronous cancellation result, scoped to the dog's owner (S13 R-13-06/12). */
public record LifecycleCancellation(String type, String id, LocalDate sessionDate) { }
