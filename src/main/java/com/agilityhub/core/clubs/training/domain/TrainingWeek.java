package com.agilityhub.core.clubs.training.domain;

import java.time.Instant;
import java.util.*;

/**
 * S09 R-09-05 counter of a unit (a dog or a booking member) for one training week `W`: the ACTIVE bookings whose
 * `weekStart = W` — sessions already done included, cancelled ones excluded — so the count follows the **session
 * date**, never the booking date (decision B15). At the limit, the cancellable ones are the future ACTIVE bookings
 * still inside `training.cancelThresholdMinutes` (R-09-10).
 */
public final class TrainingWeek {
    public record Counted(String id, String ringId, Instant startsAt, TrainingBookingState state, Instant weekStart) { }
    private TrainingWeek() { }
    public static List<Counted> counted(Collection<Counted> unitBookings, Instant weekStart) {
        return unitBookings.stream().filter(b -> b.state() == TrainingBookingState.ACTIVE && weekStart.equals(b.weekStart()))
                .sorted(Comparator.comparing(Counted::startsAt).thenComparing(Counted::id)).toList();
    }
    public static List<Counted> cancellable(Collection<Counted> counted, Instant now, int thresholdMinutes) {
        return counted.stream().filter(b -> b.state() == TrainingBookingState.ACTIVE && b.startsAt().isAfter(now)
                && TrainingRules.inTime(b.startsAt(), now, thresholdMinutes)).sorted(Comparator.comparing(Counted::startsAt).thenComparing(Counted::id)).toList();
    }
    /** R-09-07 «Qualsevol»: the first FREE ring in catalog order (the map iterates in that order), if any. */
    public static Optional<String> firstFree(Map<String, TrainingGrid.Cell> cellsInCatalogOrder) {
        return cellsInCatalogOrder.entrySet().stream().filter(e -> e.getValue().free()).map(Map.Entry::getKey).findFirst();
    }
}
