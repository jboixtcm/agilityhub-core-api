package com.agilityhub.core.clubs.bookings.application.ports;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * S10 §7 contract with S09 for the member history (25, R-10-14): the free-training bookings of the given dogs starting
 * at or after {@code from}. Declared here so `clubs.bookings` never imports `clubs.training` (which already depends on
 * bookings); the adapter lives in `clubs.training.application`. Nothing while FREE_TRAINING is off.
 */
public interface TrainingHistoryQuery {
    /** @param state ACTIVE · CANCELLED · CANCELLED_BY_CLUB (S09 §5; «done» = ACTIVE with `endsAt` passed) */
    record Item(String id, String dogId, Instant startsAt, Instant endsAt, String state) { }
    List<Item> itemsFor(Collection<String> dogIds, Instant from);
}
