package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.*;
import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * S08 R-08-10/R-08-11/R-08-21. Allowed while ACTIVE (PAYMENT_PENDING only for the system), before `classEndsAt` and
 * without a PRESENT/NO_SHOW attendance. `late` per {@link CancellationPolicy} (240 min, `bookings.lateCancelThresholdMinutes`):
 * in time → CANCELLED (pack refunded), late → CANCELLED_LATE (counts, no refund); the system and a swap are never late.
 * Every cancellation of a live booking before the class starts emits `SeatReleased` with the notice decision.
 */
@Service
public class BookingCancellationService {
    public record ClubCancellation(List<Booking> bookings, List<WaitlistEntry> waitlist) { }
    private final BookingContext context; private final BookingTransactions transactions; private final SeatLockRepository locks;
    private final BookingRepository bookings; private final SeatHoldRepository holds; private final WaitlistEntryRepository waitlist;
    private final PackBalancePort packs; private final AttendanceStatePort attendance; private final BookingEvents events;
    private final BookingCounters counters; private final BookingAudit audit; private final ClassSessionBookingAccess classes;
    private final WaitlistTransitions transitions;
    public BookingCancellationService(BookingContext context, BookingTransactions transactions, SeatLockRepository locks, BookingRepository bookings,
            SeatHoldRepository holds, WaitlistEntryRepository waitlist, PackBalancePort packs, AttendanceStatePort attendance, BookingEvents events,
            BookingCounters counters, BookingAudit audit, ClassSessionBookingAccess classes, WaitlistTransitions transitions) {
        this.context = context; this.transactions = transactions; this.locks = locks; this.bookings = bookings; this.holds = holds; this.waitlist = waitlist;
        this.packs = packs; this.attendance = attendance; this.events = events; this.counters = counters; this.audit = audit; this.classes = classes;
        this.transitions = transitions;
    }

    /** `POST /bookings/{id}/cancellation` (member, impersonating admin) and the S10 «ha avisat» call (instructor). */
    public Booking cancel(String bookingId, BookingActor actor, String message) {
        var initial = bookings.require(bookingId);
        if (actor.role() == ActorRole.INSTRUCTOR && !context.flag("bookings.instructorLastMinuteNotice")) { throw new ApiException(ErrorCode.FORBIDDEN); }
        var reason = actor.role() == ActorRole.INSTRUCTOR ? BookingCancelReason.INSTRUCTOR_NOTICE : BookingCancelReason.MEMBER;
        return transactions.write(List.of(initial.classSessionId()), () -> {
            locks.lock(initial.classSessionId());
            return cancelLocked(bookings.require(bookingId), actor, reason, message, null);
        });
    }
    /** S13/S15 cancellations (`INACTIVITY`, `LEAVE`, `PAYMENT_TIMEOUT`): always CANCELLED, never late. */
    public Booking cancelBySystem(String bookingId, BookingCancelReason reason) {
        var initial = bookings.require(bookingId);
        return transactions.write(List.of(initial.classSessionId()), () -> {
            locks.lock(initial.classSessionId());
            return cancelLocked(bookings.require(bookingId), BookingActor.system(), reason, null, null);
        });
    }
    /**
     * R-08-18 `PAYMENT_TIMEOUT` of a PAY_TO_BOOK booking (P7, `UpfrontPaymentFailed`, a provider call that failed): the
     * booking is re-read under the class's seat lock and cancelled only if it is still PAYMENT_PENDING, so two of these
     * paths racing cancel once and the loser changes nothing (E5-T10). `checkoutFailed` marks the synchronous provider
     * failure in the `BookingCancelled` payload: the member already saw the error, so it sends no N-40 (E30).
     */
    public Optional<Booking> cancelPaymentPending(String bookingId, boolean checkoutFailed) {
        var initial = bookings.findById(bookingId).orElse(null);
        if (initial == null || initial.state() != BookingState.PAYMENT_PENDING) { return Optional.empty(); }
        return transactions.write(List.of(initial.classSessionId()), () -> {
            locks.lock(initial.classSessionId());
            var current = bookings.require(bookingId);
            if (current.state() != BookingState.PAYMENT_PENDING) { return Optional.<Booking>empty(); }
            return Optional.of(cancelLocked(current, BookingActor.system(), BookingCancelReason.PAYMENT_TIMEOUT, null, null, checkoutFailed));
        });
    }
    /**
     * S15 P5c (`by = SYSTEM`, `LEAVE`) / S13 contract, not scheduled here: every live booking of the member's dogs
     * starting after now, inside the caller's transaction when there is one. {@code by} is recorded as the canceller;
     * a SYSTEM cancellation is never late.
     */
    public int cancelFutureByMember(String memberId, BookingActor by, BookingCancelReason reason) {
        var live = bookings.liveForMember(memberId, context.now());
        return transactions.write(live.stream().map(Booking::classSessionId).toList(), () -> {
            int count = 0;
            for (var b : live) {
                locks.lock(b.classSessionId()); var current = bookings.require(b.id());
                if (!BookingRepository.LIVE.contains(current.state())) { continue; }
                cancelLocked(current, by, reason, null, null); count++;
            }
            return count;
        });
    }

    /**
     * The shared transition, inside a transaction that already holds the class's seat lock.
     * @param swapToClassId R-08-09: set for the old booking of an atomic swap (CANCELLED, `SWAP`, never late) — the
     *        class of the new booking. A same-class swap keeps the class's count, so it never raises `ClassBelowMinimum`.
     */
    Booking cancelLocked(Booking b, BookingActor actor, BookingCancelReason reason, String message, String swapToClassId) {
        return cancelLocked(b, actor, reason, message, swapToClassId, false);
    }
    private Booking cancelLocked(Booking b, BookingActor actor, BookingCancelReason reason, String message, String swapToClassId, boolean checkoutFailed) {
        boolean swap = swapToClassId != null, sameClassSwap = b.classSessionId().equals(swapToClassId);
        var now = context.now();
        boolean pending = b.state() == BookingState.PAYMENT_PENDING;
        if (!(b.state() == BookingState.ACTIVE || pending && actor.isSystem()) || !now.isBefore(b.classEndsAt()) || attendance.marked(b.id())) {
            throw new ApiException(swap ? ErrorCode.SWAP_NOT_ALLOWED : ErrorCode.BOOKING_NOT_CANCELLABLE);
        }
        var outcome = CancellationPolicy.evaluate(b.classStartsAt(), now, context.integer("bookings.lateCancelThresholdMinutes"));
        boolean late = !actor.isSystem() && !swap && outcome.late();
        String refund = !late && b.packMovementId() != null ? packs.refund(b.memberId(), b.dogId(), b.id(), context.today()) : null;
        var after = bookings.update(new Booking(b.id(), b.clubId(), b.classSessionId(), b.dogId(), b.memberId(),
                late ? BookingState.CANCELLED_LATE : BookingState.CANCELLED, b.origin(), b.bookedAt(), b.bookedBy(),
                b.classStartsAt(), b.classEndsAt(), b.bookingWeekKey(), now,
                new Booking.Canceller(actor.accountId(), actor.role(), actor.displayName(), actor.impersonatedMemberId()),
                swap ? BookingCancelReason.SWAP : reason, message, late, outcome.minutesBefore(), b.swapFromBookingId(), b.swapToBookingId(),
                b.waitlistEntryId(), b.packMovementId(), refund, Booking.Charge.settled(b.charge()), b.reminderSentAt(),
                b.version() + 1, b.createdAt(), b.createdByAccountId(), now, actor.accountId()), b.version());
        var payload = new LinkedHashMap<String, Object>(); payload.put("bookingId", b.id()); payload.put("by", actor.role()); payload.put("late", late);
        payload.put("minutesBefore", outcome.minutesBefore()); payload.put("origin", actor.origin()); payload.put("reason", after.cancelReason());
        if (checkoutFailed) { payload.put("checkoutFailed", true); } // E30: payload only, no new reason value
        events.publish(BookingEvent.Kind.BookingCancelled, b.id(), payload, actor);
        if (now.isBefore(b.classStartsAt())) { seatReleased(b, now, outcome.minutesBefore(), actor); }
        counters.recount(b.classSessionId(), !late && !sameClassSwap, actor);
        if (actor.impersonated()) { audit.cancelledByClub(b, after); }
        if (late) { audit.cancelledLate(b, after); } // S14 R-14-09: every late cancellation, impersonated ones too
        return after;
    }
    private void seatReleased(Booking b, Instant now, int minutesBefore, BookingActor actor) {
        int capacity = classes.find(b.classSessionId()).map(ClassSessionBookingAccess.Session::capacity).orElse(0);
        int taken = (int) bookings.forClass(b.classSessionId(), BookingRepository.LIVE).stream().filter(x -> !x.id().equals(b.id())).count();
        boolean liveEntries = waitlist.live(b.classSessionId()).stream().anyMatch(e -> e.state() == WaitlistState.ACTIVE);
        var payload = new LinkedHashMap<String, Object>(); payload.put("classId", b.classSessionId()); payload.put("freeSeats", Math.max(0, capacity - taken));
        payload.put("minutesBefore", minutesBefore);
        payload.put("notifyWaitlist", CancellationPolicy.notifyWaitlist(context.enabled(Module.WAITLIST), b.classStartsAt(), now,
                context.integer("waitlist.notifyThresholdMinutes"), liveEntries));
        events.publish(BookingEvent.Kind.SeatReleased, b.classSessionId(), payload, actor);
    }

    /**
     * R-08-21 / S06 R-06-10: called by `ClassCancellationUseCase` inside its transaction, which already holds the class
     * (no seat lock here). Live bookings → CANCELLED_BY_CLUB (pack refunded, PAYMENT_PENDING included), live waiting-list
     * entries → CANCELLED{CLASS_CANCELLED} (R-08-16, {@link WaitlistTransitions#cancelAll}), holds
     * deleted — all or nothing. No `BookingCancelled`/`WaitlistLeft`: N-08a notifies. Counters are set by the caller.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ClubCancellation cancelByClub(String classSessionId, String reason, String adminText, String actorAccountId) {
        var now = context.now(); var cancelled = new ArrayList<Booking>();
        var cause = "RISK_REVIEW".equals(reason) ? BookingCancelReason.AUTO_CANCELLED : BookingCancelReason.CLUB_CLASS_CANCELLED;
        for (var b : bookings.forClass(classSessionId, BookingRepository.LIVE)) {
            String refund = b.packMovementId() != null ? packs.refund(b.memberId(), b.dogId(), b.id(), context.today()) : null;
            cancelled.add(bookings.update(new Booking(b.id(), b.clubId(), b.classSessionId(), b.dogId(), b.memberId(), BookingState.CANCELLED_BY_CLUB,
                    b.origin(), b.bookedAt(), b.bookedBy(), b.classStartsAt(), b.classEndsAt(), b.bookingWeekKey(), now,
                    new Booking.Canceller(actorAccountId, actorAccountId == null ? ActorRole.SYSTEM : ActorRole.ADMIN, null, null), cause, adminText,
                    false, CancellationPolicy.minutesBefore(b.classStartsAt(), now), b.swapFromBookingId(), b.swapToBookingId(), b.waitlistEntryId(),
                    b.packMovementId(), refund, Booking.Charge.settled(b.charge()), b.reminderSentAt(), b.version() + 1, b.createdAt(), b.createdByAccountId(), now, actorAccountId), b.version()));
        }
        var entries = transitions.cancelAll(classSessionId, actorAccountId);
        holds.deleteForClass(classSessionId);
        return new ClubCancellation(cancelled, entries);
    }
}
