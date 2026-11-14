package com.agilityhub.core.clubs.bookings.application.ports;

import java.time.Instant;

/**
 * BOOK-02 (owner decision, 13-11-2026), R-08-08: whether a dog holds an ACTIVE free-training booking (S09) that overlaps
 * {@code [from, to)}, so that a class booking is never made over it. Declared here so `clubs.bookings` never imports
 * `clubs.training`; the adapter lives in `clubs.training.application` (E5-T01 direction, as {@link MemberTrainingRowsPort}).
 */
public interface DogTrainingOverlapPort {
    boolean dogInTraining(String dogId, Instant from, Instant to);
}
