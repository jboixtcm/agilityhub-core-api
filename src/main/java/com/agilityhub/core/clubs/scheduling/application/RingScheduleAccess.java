package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.ClassState;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * S09 reads the ring occupation of S06 through this boundary (never the persistence types): classes DRAFT/ACTIVE
 * with a ring (§13-2: drafts block training slots) and ACTIVE ring blocks overlapping `[from, to)`, one query each.
 */
@Service
public class RingScheduleAccess {
    public record ClassInterval(String id, String ringId, Instant from, Instant to, String description) { }
    public record BlockInterval(String id, String ringId, Instant from, Instant to, String kind, String reason, String note, String createdByName, String activityId) { }
    private final ClassSessionRepository classes; private final RingBlockRepository blocks; private final SessionProjection projection;
    private final RingSlotLockRepository ringSlots;
    public RingScheduleAccess(ClassSessionRepository classes, RingBlockRepository blocks, SessionProjection projection, RingSlotLockRepository ringSlots) {
        this.classes = classes; this.blocks = blocks; this.projection = projection; this.ringSlots = ringSlots;
    }
    /**
     * R-09-13: `$inc` of the ring-slot sequences inside the caller's transaction, in instant order. A training booking
     * touches its own slot; the writes that check the ring's live bookings (S05, S06, S07) touch every grid slot their
     * range overlaps (S09 computes the grid), so the two sides conflict and bookings of other slots do not.
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockRingSlots(String ringId, Collection<Instant> slotStarts) { new TreeSet<>(slotStarts).forEach(start -> ringSlots.touch(ringId, start)); }
    public List<ClassInterval> classes(Instant from, Instant to, Locale locale) {
        return classes.between(from, to).stream().filter(c -> c.ringId() != null && (c.state() == ClassState.DRAFT || c.state() == ClassState.ACTIVE))
                .sorted(Comparator.comparing(ClassSession::startsAt).thenComparing(ClassSession::id))
                .map(c -> new ClassInterval(c.id(), c.ringId(), c.startsAt(), c.endsAt(), locale == null ? null : projection.description(c, locale))).toList();
    }
    /** @param staff with the note and the creator's name (INSTRUCTOR/ADMIN views only, R-09-12) */
    public List<BlockInterval> blocks(Instant from, Instant to, boolean staff) {
        return blocks.between(from, to).stream().sorted(Comparator.comparing(RingBlock::from).thenComparing(RingBlock::id)).map(b -> {
            var view = staff ? projection.block(b, false) : Map.<String, Object>of();
            return new BlockInterval(b.id(), b.ringId(), b.from(), b.to(), b.kind().name(), b.reason().name(), staff ? b.note() : null,
                    staff ? (String) view.get("createdByName") : null, b.activityId());
        }).toList();
    }
}
