package com.agilityhub.core.clubs.bookings.application.ports;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * S08 screen 03 (`GET /me/home`): the member's live free-training bookings (S09) as `TRAINING` rows. Declared here so
 * `clubs.bookings` never imports `clubs.training`; the adapter lives in `clubs.training.application` (E5-T01 direction).
 */
public interface MemberTrainingRowsPort {
    /** @param ringColor the ring's colour for 03's dot (E5-T25), null for a ring without one */
    record Row(String id, String dogId, Instant startsAt, Instant endsAt, String ringName, String ringColor) { }
    /** ACTIVE training bookings of the given dogs that end after {@code now}, by start. */
    List<Row> upcoming(String memberId, Collection<String> dogIds, Instant now);
}
