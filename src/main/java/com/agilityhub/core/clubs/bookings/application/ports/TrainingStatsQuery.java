package com.agilityhub.core.clubs.bookings.application.ports;

import java.time.Instant;

/**
 * S10 §7 contract with S09 for the 22/D13 metric (R-10-08): the ACTIVE free-training bookings of one dog (§13-8: per
 * dog) whose `endsAt` falls in `[from, to)`. Adapter in `clubs.training.application`; 0 while FREE_TRAINING is off.
 */
public interface TrainingStatsQuery {
    int countDone(String dogId, Instant from, Instant to);
}
