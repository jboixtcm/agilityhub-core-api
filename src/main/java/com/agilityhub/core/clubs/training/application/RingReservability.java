package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.catalogs.application.RingTrainingBookings;
import com.agilityhub.core.clubs.census.application.TrainingMemberAccess;
import com.agilityhub.core.clubs.training.domain.TrainingCancelReason;
import com.agilityhub.core.clubs.training.persistence.*;
import java.util.*;

/**
 * S09 R-09-13 for S05 R-05-08 (organizer ruling 2026-09-24): the future ACTIVE bookings of a ring that stops being
 * reservable, and their cancellation by the club (`CANCELLED_BY_CLUB` / `RING_NOT_RESERVABLE`, `TrainingCancelled{by:
 * ADMIN, origin: BACKOFFICE}`, N-47) inside the S05 catalog transaction.
 */
public class RingReservability implements RingTrainingBookings {
    private final TrainingContext context; private final TrainingBookingRepository bookings; private final TrainingBookingService service;
    private final TrainingMemberAccess census; private final TrainingSlotLocks slotLocks;
    public RingReservability(TrainingContext context, TrainingBookingRepository bookings, TrainingBookingService service, TrainingMemberAccess census,
            TrainingSlotLocks slotLocks) {
        this.context = context; this.bookings = bookings; this.service = service; this.census = census; this.slotLocks = slotLocks;
    }
    @Override public List<Booking> futureActive(String ringId) {
        var active = bookings.activeAfter("ringId", ringId, context.now());
        var names = census.firstNames(active.stream().map(TrainingBooking::memberId).distinct().toList());
        var dogs = new HashMap<String, String>(); census.dogs(active.stream().map(TrainingBooking::dogId).distinct().toList()).forEach(d -> dogs.put(d.id(), d.name()));
        return active.stream().map(b -> new Booking(b.id(), b.ringId(), b.startsAt(), b.endsAt(), Objects.toString(names.get(b.memberId()), ""),
                Objects.toString(dogs.get(b.dogId()), ""))).toList();
    }
    @Override public void lockBookableSlots(String ringId) { slotLocks.bookable(ringId); }
    @Override public void cancelNotReservable(List<String> bookingIds) { service.cancelByClub(bookingIds, TrainingCancelReason.RING_NOT_RESERVABLE, null); }
}
