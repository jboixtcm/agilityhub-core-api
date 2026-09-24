package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.bookings.application.BookingQueryService;
import com.agilityhub.core.clubs.bookings.application.ports.InactivityPort;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.TrainingMemberAccess;
import com.agilityhub.core.clubs.training.domain.*;
import com.agilityhub.core.clubs.training.persistence.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * S09 R-09-05…10, R-09-13/14/16. A booking is one Mongo transaction retried whole ({@link TrainingTransactions}):
 * (1) `$inc` of the unit's `trainingSeq` (serialises the weekly counter); (2) eligibility, ring, member conditions,
 * grid and window; (3) no overlapping training or class booking of the dog; (4) the counter of the slot's training
 * week (by session date) below `training.maxPerWeek` unless an impersonating admin overrides it; (5) the live slot
 * state («Qualsevol» = first FREE ring in catalog order); (6) insert with the lowest free `seatIndex`, the partial
 * unique index being the final guard; (7) `Member.lastDogForTraining`, audit when impersonated, `TrainingBooked`.
 */
@Service
public class TrainingBookingService {
    public record Override(boolean limit, String reason) { }
    public record Counter(int used, int limit, Instant weekStart, Instant weekEnd) { public int remaining() { return Math.max(0, limit - used); } }
    public record Booked(TrainingBooking booking, Counter counter) { }
    private final TrainingContext context; private final TrainingTransactions transactions; private final TrainingBookingRepository bookings;
    private final TrainingMemberAccess census; private final TrainingEligibilityService eligibility; private final TrainingSlotService slots;
    private final BookingQueryService classBookings; private final InactivityPort inactivity; private final TrainingEvents events; private final TrainingAudit audit;
    private final PlanningCatalogAccess catalogs;
    public TrainingBookingService(TrainingContext context, TrainingTransactions transactions, TrainingBookingRepository bookings, TrainingMemberAccess census,
            TrainingEligibilityService eligibility, TrainingSlotService slots, BookingQueryService classBookings, InactivityPort inactivity, TrainingEvents events,
            TrainingAudit audit, PlanningCatalogAccess catalogs) {
        this.context = context; this.transactions = transactions; this.bookings = bookings; this.census = census; this.eligibility = eligibility;
        this.slots = slots; this.classBookings = classBookings; this.inactivity = inactivity; this.events = events; this.audit = audit; this.catalogs = catalogs;
    }
    /** The in-process lanes of a booking: the dog, the booking member and the slot instant. */
    public static List<String> lanes(String dogId, String memberId, Instant startsAt) { return List.of("dog:" + dogId, "member:" + memberId, "slot:" + startsAt); }

    public Booked book(TrainingActor actor, String dogId, Instant startsAt, String ringId, Override override, String idempotencyKey) {
        if (override != null && !actor.impersonated()) { throw new ApiException(ErrorCode.OVERRIDE_NOT_ALLOWED); }
        return transactions.write(lanes(dogId, actor.memberId(), startsAt), () -> bookInTransaction(actor, dogId, startsAt, ringId, override, idempotencyKey));
    }
    private Booked bookInTransaction(TrainingActor actor, String dogId, Instant startsAt, String ringId, Override override, String idempotencyKey) {
        var now = context.now(); var zone = context.zone(); int slotMinutes = context.slotMinutes();
        var dog = census.reachableDogs(actor.memberId()).stream().map(Map.Entry::getKey).filter(d -> d.id().equals(dogId)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE));
        boolean dogUnit = context.dogUnit();
        census.touchTrainingSeq(dogUnit, dogUnit ? dog.id() : actor.memberId()); // (1)
        // (2) eligibility, ring, member conditions, grid, window
        if (!eligibility.canFreeTrain(dog)) { throw new ApiException(ErrorCode.DOG_NOT_ALLOWED); }
        var bookable = slots.bookableRings();
        if (ringId != null) {
            var ring = catalogs.trainingRings().stream().filter(r -> r.id().equals(ringId)).findFirst().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            if (!ring.active() || !ring.allowsFreeTraining()) { throw new ApiException(ErrorCode.RING_NOT_RESERVABLE); }
        }
        var booker = census.member(actor.memberId()).orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_ACTIVE));
        if (!booker.active()) { throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE); }
        if (booker.blocked()) { throw new ApiException(ErrorCode.BOOKING_BLOCKED, Map.of("reason", Objects.toString(booker.blockReason(), ""))); }
        var date = startsAt.atZone(zone).toLocalDate();
        if (context.enabled(Module.INACTIVITY)) {
            inactivity.covering(booker.id(), date).ifPresent(p -> {
                var details = new LinkedHashMap<String, Object>(); details.put("from", p.from().toString()); if (p.to() != null) { details.put("to", p.to().toString()); }
                throw new ApiException(ErrorCode.INACTIVITY_PERIOD, details);
            });
        }
        switch (TrainingGrid.placement(startsAt, context.openingHours().get(date.getDayOfWeek()), context.holidays().contains(date), zone, slotMinutes)) {
            case CLOSED -> throw new ApiException(ErrorCode.CLUB_CLOSED);
            case OFF_GRID -> throw new ApiException(ErrorCode.SLOT_NOT_ON_GRID);
            case ON_GRID -> { }
        }
        var today = context.today(); int windowDays = context.windowDays();
        if (!startsAt.isAfter(now) || !TrainingRules.inWindow(date, today, windowDays)) {
            throw new ApiException(ErrorCode.SLOT_OUT_OF_WINDOW, Map.of("from", today.toString(), "to", today.plusDays(windowDays).toString()));
        }
        var slot = new TrainingGrid.Slot(date, startsAt, startsAt.plus(Duration.ofMinutes(slotMinutes)));
        // (3) the dog is nowhere else at that time
        if (!bookings.activeForDog(dog.id(), slot.startsAt(), slot.endsAt()).isEmpty() || classBookings.dogInClass(dog.id(), slot.startsAt(), slot.endsAt())) {
            throw new ApiException(ErrorCode.DOG_ALREADY_BOOKED);
        }
        // (4) the weekly counter, by session date
        var week = context.week(startsAt); var counted = counted(dogUnit, dog.id(), actor.memberId(), week.start());
        int limit = context.maxPerWeek();
        if (counted.size() >= limit && (override == null || !override.limit())) {
            var details = new LinkedHashMap<String, Object>(); details.put("limit", limit); details.put("used", counted.size());
            details.put("weekStart", week.start().toString()); details.put("weekEnd", week.end().toString()); details.put("cancellableBookings", cancellable(counted, now));
            throw new ApiException(ErrorCode.TRAINING_LIMIT_REACHED, details);
        }
        // (5) the live slot state
        var rings = bookable.stream().map(slots::ring).toList();
        var cells = slots.liveCells(slot, rings, dog.id());
        String chosen = ringId != null ? ringId : TrainingWeek.firstFree(cells).orElse(null);
        var cell = chosen == null ? null : cells.get(chosen);
        if (cell == null || !cell.free()) { throw slotTaken(ringId, startsAt, cells, cell); }
        // (6) insert with the lowest free seat; the partial unique index is the final guarantee
        var id = UUID.randomUUID().toString();
        var booking = bookings.insert(new TrainingBooking(id, TenantContext.require(), actor.memberId(), dog.id(), chosen, slot.startsAt(), slot.endsAt(),
                slot.slotId(chosen), cell.freeSeat(), week.start(), TrainingBookingState.ACTIVE, actor.origin(), actor.accountId(),
                actor.impersonated() ? actor.accountId() : null, null, null, null, null, idempotencyKey, null, null, now, now, actor.accountId()));
        // (7) memory of the last dog, audit, outbox
        census.lastDogForTraining(actor.memberId(), dog.id());
        if (actor.impersonated()) { audit.bookedByClub(booking, override == null ? null : override.reason()); }
        events.booked(booking, actor);
        return new Booked(booking, new Counter(counted.size() + 1, limit, week.start(), week.end()));
    }
    private static ApiException slotTaken(String ringId, Instant startsAt, Map<String, TrainingGrid.Cell> cells, TrainingGrid.Cell cell) {
        var free = cells.entrySet().stream().filter(e -> e.getValue().free()).map(Map.Entry::getKey).toList();
        SlotReason reason = cell != null ? cell.reason()
                : cells.values().stream().anyMatch(c -> c.state() == SlotState.BOOKED) || cells.isEmpty() ? SlotReason.TRAINING : cells.values().iterator().next().reason();
        if (reason == SlotReason.OWN_TRAINING) { reason = SlotReason.TRAINING; }
        var details = new LinkedHashMap<String, Object>(); if (ringId != null) { details.put("ringId", ringId); } // «Qualsevol»: no ring
        details.put("startsAt", startsAt.toString());
        details.put("reason", reason); details.put("freeRings", free);
        return new ApiException(ErrorCode.SLOT_TAKEN, details);
    }
    /** R-09-05: the counted bookings of the unit's training week (see {@link TrainingWeek#counted}). */
    List<TrainingWeek.Counted> counted(boolean dogUnit, String dogId, String memberId, Instant weekStart) {
        String value = dogUnit ? dogId : memberId;
        if (value == null) { return List.of(); }
        return TrainingWeek.counted(bookings.week(dogUnit ? "dogId" : "memberId", value, weekStart).stream()
                .map(b -> new TrainingWeek.Counted(b.id(), b.ringId(), b.startsAt(), b.state(), b.weekStart())).toList(), weekStart);
    }
    /** R-09-05 / T-09-10: the future ACTIVE bookings of the week still inside the cancellation threshold. */
    List<Map<String, Object>> cancellable(List<TrainingWeek.Counted> week, Instant now) {
        int threshold = context.cancelThreshold(); var names = ringNames();
        return TrainingWeek.cancellable(week, now, threshold).stream().map(b -> {
            var out = new LinkedHashMap<String, Object>(); out.put("id", b.id()); out.put("startsAt", b.startsAt().toString());
            out.put("ringName", Objects.toString(names.get(b.ringId()), "")); out.put("cancellableUntil", TrainingRules.cancellableUntil(b.startsAt(), threshold).toString());
            return (Map<String, Object>) out;
        }).toList();
    }
    Map<String, String> ringNames() {
        var result = new HashMap<String, String>(); catalogs.trainingRings().forEach(r -> result.put(r.id(), r.name())); return result;
    }
    /** R-09-05: the counter of the unit for the training week containing {@code instant}. */
    public Counter counter(String dogId, String memberId, Instant instant) {
        var week = context.week(instant);
        return new Counter(counted(context.dogUnit(), dogId, memberId, week.start()).size(), context.maxPerWeek(), week.start(), week.end());
    }

    /**
     * R-09-10: a member (or the impersonation token) cancels while `now ≤ startsAt − training.cancelThresholdMinutes`;
     * later only the impersonating admin, with a reason (ADMIN_LATE, audited). A booking block never prevents it.
     */
    public TrainingBooking cancel(String id, TrainingActor actor, String reason) {
        var initial = bookings.require(id);
        return transactions.write(List.of("dog:" + initial.dogId(), "member:" + initial.memberId()), () -> {
            var b = bookings.require(id); var now = context.now(); int threshold = context.cancelThreshold();
            if (b.state() != TrainingBookingState.ACTIVE) { throw new ApiException(ErrorCode.INVALID_STATE); }
            boolean late = !TrainingRules.inTime(b.startsAt(), now, threshold);
            var cancelReason = TrainingCancelReason.MEMBER_REQUEST; var by = TrainingCancelledBy.MEMBER;
            if (late) {
                if (!actor.impersonated() || !now.isBefore(b.endsAt())) {
                    throw new ApiException(ErrorCode.TRAINING_CANCEL_TOO_LATE, Map.of("thresholdMinutes", threshold, "minutesBefore", TrainingRules.minutesBefore(b.startsAt(), now)));
                }
                if (reason == null || reason.isBlank()) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "reason")); }
                cancelReason = TrainingCancelReason.ADMIN_LATE; by = TrainingCancelledBy.ADMIN;
            }
            var after = transition(b, TrainingBookingState.CANCELLED, by, cancelReason, reason, actor.accountId(), now);
            if (actor.impersonated()) { audit.cancelledByClub(b, after, reason); }
            events.cancelled(after, actor.as(by), late);
            return after;
        });
    }
    private TrainingBooking transition(TrainingBooking b, TrainingBookingState state, TrainingCancelledBy by, TrainingCancelReason reason, String note, String account, Instant now) {
        long version = b.version() == null ? 0L : b.version();
        return bookings.update(new TrainingBooking(b.id(), b.clubId(), b.memberId(), b.dogId(), b.ringId(), b.startsAt(), b.endsAt(), b.slotId(), b.seatIndex(),
                b.weekStart(), state, b.origin(), b.createdByAccountId(), b.impersonatedByAccountId(), now, by, reason, note, b.idempotencyKey(), b.reminderSentAt(),
                version + 1, b.createdAt(), now, account), version);
    }

    /**
     * R-09-13: the `TrainingConflictPort` half — inside the caller's S05/S06/S07 transaction, each still ACTIVE booking
     * → CANCELLED_BY_CLUB with `cancelReason ∈ RING_BLOCK · CLASS_CONFLICT · RING_NOT_RESERVABLE` and
     * `TrainingCancelled{by: ADMIN, origin: BACKOFFICE}` (N-47).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<TrainingBooking> cancelByClub(List<String> bookingIds, TrainingCancelReason reason, String adminText) {
        var actor = TrainingActor.club(); var now = context.now(); var result = new ArrayList<TrainingBooking>();
        for (String id : bookingIds) {
            var b = bookings.findById(id).orElse(null);
            if (b == null || b.state() != TrainingBookingState.ACTIVE) { continue; }
            var after = transition(b, TrainingBookingState.CANCELLED_BY_CLUB, TrainingCancelledBy.ADMIN, reason, adminText, actor.accountId(), now);
            events.cancelled(after, actor, false); result.add(after);
        }
        return result;
    }
    /** S15 P5c / S13 leave (E8 schedules it): the member's future ACTIVE bookings → CANCELLED (`SYSTEM`, reason; no N-07 for MEMBER_LEFT). */
    public int cancelFutureByMember(String memberId, TrainingCancelledBy by, TrainingCancelReason reason) {
        return system(bookings.activeAfter("memberId", memberId, context.now()), by, reason);
    }
    /** S13 `InactivityResolved{APPROVED}` (E8): ACTIVE bookings of the member whose local session date is inside `[from, to]`. */
    public int cancelForInactivity(String memberId, LocalDate from, LocalDate to) {
        if (!context.enabled(Module.INACTIVITY) || !context.flag("inactivity.cancelBookingsOnApproval")) { return 0; }
        var zone = context.zone();
        var inside = bookings.activeAfter("memberId", memberId, context.now()).stream().filter(b -> {
            var date = b.startsAt().atZone(zone).toLocalDate(); return !date.isBefore(from) && (to == null || !date.isAfter(to));
        }).toList();
        return system(inside, TrainingCancelledBy.SYSTEM, TrainingCancelReason.INACTIVITY);
    }
    /** S03 `DogDeactivated`: the dog's future ACTIVE bookings (`SYSTEM`, `MEMBER_LEFT`, no N-07). */
    public int cancelForDog(String dogId) { return system(bookings.activeAfter("dogId", dogId, context.now()), TrainingCancelledBy.SYSTEM, TrainingCancelReason.MEMBER_LEFT); }
    private int system(List<TrainingBooking> candidates, TrainingCancelledBy by, TrainingCancelReason reason) {
        if (candidates.isEmpty()) { return 0; }
        var keys = new ArrayList<String>(); candidates.forEach(b -> { keys.add("dog:" + b.dogId()); keys.add("member:" + b.memberId()); });
        return transactions.write(keys, () -> {
            int count = 0; var now = context.now(); var actor = TrainingActor.system().as(by);
            for (var candidate : candidates) {
                var b = bookings.require(candidate.id());
                if (b.state() != TrainingBookingState.ACTIVE) { continue; }
                events.cancelled(transition(b, TrainingBookingState.CANCELLED, by, reason, null, null, now), actor, false); count++;
            }
            return count;
        });
    }
}
