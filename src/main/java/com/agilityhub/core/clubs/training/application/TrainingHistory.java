package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.bookings.application.ports.TrainingHistoryQuery;
import com.agilityhub.core.clubs.bookings.application.ports.TrainingStatsQuery;
import com.agilityhub.core.clubs.training.persistence.TrainingBookingRepository;
import com.agilityhub.core.platform.application.Module;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/** S09 → S10 (§7): the free-training rows of the member history (25) and the trainings of the 22/D13 metric. */
@Service
public class TrainingHistory implements TrainingHistoryQuery, TrainingStatsQuery {
    private final TrainingBookingRepository bookings; private final TrainingContext context;
    public TrainingHistory(TrainingBookingRepository bookings, TrainingContext context) { this.bookings = bookings; this.context = context; }

    @Override public List<Item> itemsFor(Collection<String> dogIds, Instant from) {
        if (!context.enabled(Module.FREE_TRAINING)) { return List.of(); }
        return bookings.forDogsSince(dogIds, from).stream().map(b -> new Item(b.id(), b.dogId(), b.startsAt(), b.endsAt(), b.state().name())).toList();
    }
    @Override public int countDone(String dogId, Instant from, Instant to) {
        return context.enabled(Module.FREE_TRAINING) ? (int) bookings.countActiveEndingBetween(dogId, from, to) : 0;
    }
}
