package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.census.application.TrainingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ports.TrainingConflictPort;
import com.agilityhub.core.clubs.training.domain.TrainingCancelReason;
import com.agilityhub.core.clubs.training.persistence.*;
import java.time.Instant;
import java.util.*;

/**
 * S09 R-09-13, the real {@link TrainingConflictPort}: S06 (class creation/move, ring blocks from D4 and 24) and S07
 * (activity publication) list the live bookings of a ring window and, when an ADMIN sends `cancelBookings: true`,
 * cancel them inside their own transaction (`Propagation.MANDATORY`). The caller's reason maps to the catalog
 * `cancelReason`: `RING_BLOCK`, `CLASS_CONFLICT` (S06 passes `CLASS_SESSION`) or `RING_NOT_RESERVABLE`.
 */
public class TrainingConflictService implements TrainingConflictPort {
    private final TrainingBookingRepository bookings; private final TrainingBookingService service; private final TrainingMemberAccess census;
    private final TrainingSlotLocks slotLocks;
    public TrainingConflictService(TrainingBookingRepository bookings, TrainingBookingService service, TrainingMemberAccess census, TrainingSlotLocks slotLocks) {
        this.bookings = bookings; this.service = service; this.census = census; this.slotLocks = slotLocks;
    }
    @Override public List<Booking> findActiveBookings(String ringId, Instant from, Instant to) {
        var active = bookings.activeBetween(from, to, ringId);
        var names = census.firstNames(active.stream().map(TrainingBooking::memberId).distinct().toList());
        var dogs = new HashMap<String, String>(); census.dogs(active.stream().map(TrainingBooking::dogId).distinct().toList()).forEach(d -> dogs.put(d.id(), d.name()));
        return active.stream().map(b -> new Booking(b.id(), b.ringId(), b.startsAt(), b.endsAt(), Objects.toString(names.get(b.memberId()), ""),
                Objects.toString(dogs.get(b.dogId()), ""))).toList();
    }
    @Override public void cancelByClub(List<String> bookingIds, String cancelReason) { service.cancelByClub(bookingIds, reason(cancelReason), null); }
    @Override public void lockSlots(String ringId, Instant from, Instant to) { slotLocks.overlapping(ringId, from, to); }
    static TrainingCancelReason reason(String value) {
        if (value == null) { return TrainingCancelReason.RING_BLOCK; }
        return switch (value) {
            case "CLASS_SESSION", "CLASS_CONFLICT" -> TrainingCancelReason.CLASS_CONFLICT;
            case "RING_NOT_RESERVABLE" -> TrainingCancelReason.RING_NOT_RESERVABLE;
            default -> TrainingCancelReason.RING_BLOCK;
        };
    }
}
