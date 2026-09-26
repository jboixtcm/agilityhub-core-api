package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.census.application.TrainingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.RingScheduleAccess;
import com.agilityhub.core.clubs.scheduling.application.ports.TrainingOccupancyPort;
import com.agilityhub.core.clubs.training.persistence.*;
import com.agilityhub.core.platform.application.Module;
import java.time.Instant;
import java.util.*;

/**
 * S09 R-09-12, the real {@link TrainingOccupancyPort} behind the S06 day grids (10, 23, D12, D4): ACTIVE training
 * bookings (`training.slotMinutes` long) and ACTIVE ring blocks overlapping the range. A MEMBER viewer gets
 * `{type, reason}` only — no booking or block id, member, dog or note (E5-T09);
 * INSTRUCTOR/ADMIN get the ids, the booker's first name, the dog and the block note. With FREE_TRAINING off only blocks.
 */
public class TrainingOccupancyService implements TrainingOccupancyPort {
    private final TrainingContext context; private final TrainingBookingRepository bookings; private final RingScheduleAccess schedule;
    private final TrainingMemberAccess census;
    public TrainingOccupancyService(TrainingContext context, TrainingBookingRepository bookings, RingScheduleAccess schedule, TrainingMemberAccess census) {
        this.context = context; this.bookings = bookings; this.schedule = schedule; this.census = census;
    }
    @Override public List<Interval> occupancy(Instant from, Instant to, Collection<String> ringIds, String viewerRole) {
        boolean member = viewerRole == null || "MEMBER".equals(viewerRole);
        var result = new ArrayList<Interval>();
        if (context.enabled(Module.FREE_TRAINING)) {
            var active = bookings.activeBetween(from, to, null).stream().filter(b -> ringIds == null || ringIds.contains(b.ringId())).toList();
            var names = member ? Map.<String, String>of() : census.firstNames(active.stream().map(TrainingBooking::memberId).distinct().toList());
            var dogs = new HashMap<String, String>();
            var dogIds = active.stream().map(TrainingBooking::dogId).distinct().toList();
            if (!member) { census.dogs(dogIds).forEach(d -> dogs.put(d.id(), d.name())); }
            // S10 R-10-00 (E6-T02): the guide of «{guia} + {gos}» is the dog's handler when it has one.
            var handlers = member ? Map.<String, String>of() : census.handlerNames(dogIds);
            for (var b : active) {
                result.add(member ? new Interval(b.ringId(), b.startsAt(), b.endsAt(), Type.TRAINING, "TRAINING", null, null, null, null)
                        : new Interval(b.ringId(), b.startsAt(), b.endsAt(), Type.TRAINING, "TRAINING", handlers.getOrDefault(b.dogId(), names.get(b.memberId())),
                                dogs.get(b.dogId()), null, b.id()));
            }
        }
        for (var block : schedule.blocks(from, to, !member)) {
            if (ringIds != null && !ringIds.contains(block.ringId())) { continue; }
            result.add(new Interval(block.ringId(), block.from(), block.to(), Type.RING_BLOCK, block.reason(), null, null, member ? null : block.note(), member ? null : block.id()));
        }
        result.sort(Comparator.comparing(Interval::from).thenComparing(Interval::ringId).thenComparing(i -> Objects.toString(i.id(), "")));
        return result;
    }
}
