package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.scheduling.application.RingScheduleAccess;
import com.agilityhub.core.clubs.training.domain.TrainingGrid;
import com.agilityhub.core.platform.application.Module;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S09 R-09-13: which ring-slot sequences (`ring_slot_locks`) a write touches inside its Mongo transaction. A booking
 * touches its own slot; a write that checks the ring's live bookings touches every training grid slot of the ring its
 * range overlaps, so a concurrent booking of any of those slots conflicts with it in Mongo (no write skew) and bookings
 * of other slots never do. S09 owns the grid (`club.openingHours`, `club.holidays`, `training.slotMinutes`), so S05,
 * S06 and S07 reach this through their ports.
 */
@Service
public class TrainingSlotLocks {
    private final TrainingContext context; private final RingScheduleAccess schedule;
    public TrainingSlotLocks(TrainingContext context, RingScheduleAccess schedule) { this.context = context; this.schedule = schedule; }

    /** S06 ring block / class moved onto the ring, S07 activity block: the grid slots of the ring overlapping `[from, to)`. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void overlapping(String ringId, Instant from, Instant to) {
        if (!context.enabled(Module.FREE_TRAINING)) { return; } // no training bookings to conflict with
        var starts = starts(from, to);
        if (!starts.isEmpty()) { schedule.lockRingSlots(ringId, starts); }
    }
    /** S05 (ring deactivated or `allowsFreeTraining` off): every slot a booking can still take, now → end of the window. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bookable(String ringId) {
        var end = context.today().plusDays(context.windowDays() + 1L).atStartOfDay(context.zone()).toInstant();
        overlapping(ringId, context.now(), end);
    }
    /** The starts of the grid slots overlapping `[from, to)`, over every club-local day the range touches. */
    List<Instant> starts(Instant from, Instant to) {
        var zone = context.zone(); var hours = context.openingHours(); var holidays = context.holidays(); int minutes = context.slotMinutes();
        var result = new ArrayList<Instant>();
        for (var date = from.atZone(zone).toLocalDate(); date.atStartOfDay(zone).toInstant().isBefore(to); date = date.plusDays(1)) {
            for (var slot : TrainingGrid.slots(date, hours.get(date.getDayOfWeek()), holidays.contains(date), zone, minutes)) {
                if (slot.startsAt().isBefore(to) && slot.endsAt().isAfter(from)) { result.add(slot.startsAt()); }
            }
        }
        return result;
    }
}
