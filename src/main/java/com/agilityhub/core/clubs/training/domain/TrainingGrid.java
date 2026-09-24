package com.agilityhub.core.clubs.training.domain;

import java.time.*;
import java.util.*;

/**
 * S09 R-09-03 computed grid, never persisted. A club-local day is closed on a holiday or without opening hours;
 * otherwise slots of `training.slotMinutes` run from the opening hour while `start + slotMinutes ≤ close`. Instants
 * come from `ZonedDateTime.of(date, time, zone)`: a local time inside a DST gap shifts forward, an ambiguous one
 * takes the first occurrence. A cell's state follows the priority class → ring block → bookings ≥ capacity → free,
 * with «overlap» = `a.from < slot.endsAt ∧ a.to > slot.startsAt`.
 */
public final class TrainingGrid {
    public record Hours(LocalTime open, LocalTime close) {
        public Hours { Objects.requireNonNull(open); Objects.requireNonNull(close); }
    }
    public record Slot(LocalDate date, Instant startsAt, Instant endsAt) {
        /** `"{ringId}_{startsAt ISO UTC}"`: the documented exception to the UUID convention (§13-9). */
        public String slotId(String ringId) { return slotId(ringId, startsAt); }
        public static String slotId(String ringId, Instant startsAt) { return ringId + "_" + startsAt; }
    }
    public record Ring(String id, int capacity) { }
    /** A class session or a ring block occupying a ring. */
    public record Busy(String id, String ringId, Instant from, Instant to) {
        public boolean overlaps(Instant start, Instant end) { return from.isBefore(end) && to.isAfter(start); }
    }
    /** An ACTIVE training booking holding one seat. */
    public record Seat(String bookingId, String ringId, String dogId, Instant from, Instant to, int seatIndex) {
        public boolean overlaps(Instant start, Instant end) { return from.isBefore(end) && to.isAfter(start); }
    }
    public record Cell(SlotState state, SlotReason reason, int capacity, List<Seat> seats, List<Busy> classes, List<Busy> blocks, String ownBookingId) {
        public Cell { seats = List.copyOf(seats); classes = List.copyOf(classes); blocks = List.copyOf(blocks); }
        public boolean free() { return state == SlotState.FREE; }
        /** Lowest seat index not held by an overlapping booking (R-09-06 step 6). */
        public int freeSeat() {
            var taken = new HashSet<Integer>(); seats.forEach(s -> taken.add(s.seatIndex()));
            int index = 0; while (taken.contains(index)) { index++; }
            return index;
        }
    }
    public enum Placement { ON_GRID, OFF_GRID, CLOSED }
    private TrainingGrid() { }

    /** Steps 1–2: the slots of one local day; empty when the club is closed. */
    public static List<Slot> slots(LocalDate date, Hours hours, boolean holiday, ZoneId zone, int slotMinutes) {
        if (holiday || hours == null || slotMinutes <= 0) { return List.of(); }
        var result = new ArrayList<Slot>();
        int open = hours.open().toSecondOfDay() / 60, close = hours.close().toSecondOfDay() / 60;
        for (int start = open; start + slotMinutes <= close; start += slotMinutes) {
            var startsAt = ZonedDateTime.of(date, LocalTime.ofSecondOfDay(start * 60L), zone).toInstant();
            // Inside a spring gap two local times shift to one instant: keep the first, never a duplicate slot.
            if (!result.isEmpty() && !startsAt.isAfter(result.getLast().startsAt())) { continue; }
            result.add(new Slot(date, startsAt, startsAt.plus(Duration.ofMinutes(slotMinutes))));
        }
        return result;
    }

    /** Step 4 for one `(ring, slot)`; `dogId` marks the queried dog's own booking as OWN_TRAINING. */
    public static Cell cell(Ring ring, Slot slot, Collection<Busy> classes, Collection<Busy> blocks, Collection<Seat> seats, String dogId) {
        var c = classes.stream().filter(b -> b.ringId().equals(ring.id()) && b.overlaps(slot.startsAt(), slot.endsAt())).toList();
        var r = blocks.stream().filter(b -> b.ringId().equals(ring.id()) && b.overlaps(slot.startsAt(), slot.endsAt())).toList();
        var s = seats.stream().filter(b -> b.ringId().equals(ring.id()) && b.overlaps(slot.startsAt(), slot.endsAt()))
                .sorted(Comparator.comparingInt(Seat::seatIndex).thenComparing(Seat::bookingId)).toList();
        String own = dogId == null ? null : s.stream().filter(b -> dogId.equals(b.dogId())).map(Seat::bookingId).findFirst().orElse(null);
        if (!c.isEmpty()) { return new Cell(SlotState.BLOCKED, SlotReason.CLASS, ring.capacity(), s, c, r, own); }
        if (!r.isEmpty()) { return new Cell(SlotState.BLOCKED, SlotReason.RING_BLOCK, ring.capacity(), s, c, r, own); }
        if (s.size() >= ring.capacity()) {
            return new Cell(SlotState.BOOKED, own != null ? SlotReason.OWN_TRAINING : SlotReason.TRAINING, ring.capacity(), s, c, r, own);
        }
        return new Cell(SlotState.FREE, null, ring.capacity(), s, c, r, own);
    }

    /**
     * R-09-06 step 2 / T-09-14: whether an instant is a slot of the grid. A holiday, a day without hours or a time outside
     * `[open, close − slotMinutes]` is CLOSED; a time inside the opening hours but not on the grid is OFF_GRID.
     */
    public static Placement placement(Instant startsAt, Hours hours, boolean holiday, ZoneId zone, int slotMinutes) {
        var local = startsAt.atZone(zone);
        var slots = slots(local.toLocalDate(), hours, holiday, zone, slotMinutes);
        if (slots.isEmpty()) { return Placement.CLOSED; }
        if (slots.stream().anyMatch(s -> s.startsAt().equals(startsAt))) { return Placement.ON_GRID; }
        int minute = local.toLocalTime().toSecondOfDay() / 60;
        int open = hours.open().toSecondOfDay() / 60, close = hours.close().toSecondOfDay() / 60;
        return minute < open || minute + slotMinutes > close ? Placement.CLOSED : Placement.OFF_GRID;
    }
}
