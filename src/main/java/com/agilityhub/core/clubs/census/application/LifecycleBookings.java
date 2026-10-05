package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.application.ports.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;

/** All three owners join the same Mongo transaction; a failed cancellation rolls back the complete decision. */
@Service
public class LifecycleBookings {
    private final BookingCancellationPort bookings;
    private final TrainingCancellationPort trainings;
    private final ActivityCancellationPort activities;
    public LifecycleBookings(BookingCancellationPort bookings, TrainingCancellationPort trainings, ActivityCancellationPort activities) {
        this.bookings = bookings; this.trainings = trainings; this.activities = activities;
    }
    public List<LifecycleCancellation> inside(String memberId, LocalDate from, LocalDate to, boolean cancel, boolean leave) {
        var result = new ArrayList<>(bookings.inside(memberId, from, to, cancel, leave));
        result.addAll(trainings.inside(memberId, from, to, cancel, leave));
        result.addAll(activities.inside(memberId, from, to, cancel, leave));
        return List.copyOf(result);
    }
}
