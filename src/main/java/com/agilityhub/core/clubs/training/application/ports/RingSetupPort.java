package com.agilityhub.core.clubs.training.application.ports;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * S09 R-09-15 / S16: the ACTIVE `RingSetup` behind `Ring.activeSetupId`, summarised for `rings[].setup` of
 * `GET /training-slots` (level names in the reader's locale). S16 (courses) supplies the real adapter; until then
 * the default resolves nothing, so no `setup` is published even with COURSES on.
 */
public interface RingSetupPort {
    record Setup(String id, String kind, List<String> levelNames, Instant builtAt, LocalDate expectedUntil) { }
    Optional<Setup> active(String setupId, Locale locale);
}
