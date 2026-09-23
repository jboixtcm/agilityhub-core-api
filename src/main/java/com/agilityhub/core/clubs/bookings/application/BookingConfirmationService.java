package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.*;
import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * S08 R-08-08 confirmation and R-08-09 atomic swap in one transaction: the hold must be the caller's and live;
 * every check runs again; at the limit the chosen swappable booking is cancelled first (CANCELLED/SWAP, pack
 * refunded, `SeatReleased`); the booking is created ACTIVE — or PAYMENT_PENDING with a checkout for PAY_TO_BOOK
 * (R-08-18) — with the class times and week denormalised; pack consumed, hold deleted, waiting-list entry
 * consolidated, `Member.lastDogForClass` of the booker written, `BookingCreated` + `SeatHoldReleased` emitted.
 */
@Service
public class BookingConfirmationService {
    public record Confirmed(Booking booking, String checkoutUrl) { }
    private final BookingContext context; private final BookingTransactions transactions; private final BookingChecks checks;
    private final SeatLockRepository locks; private final SeatHoldRepository holds; private final BookingRepository bookings;
    private final BookingCancellationService cancellations; private final PackBalancePort packs; private final WaitlistConsolidationPort waitlist;
    private final SingleClassChargePort charges; private final BookingMemberAccess census; private final BookingEvents events;
    private final BookingCounters counters; private final BookingAudit audit; private final BookingViews views; private final SeatHoldService seatHolds;
    public BookingConfirmationService(BookingContext context, BookingTransactions transactions, BookingChecks checks, SeatLockRepository locks,
            SeatHoldRepository holds, BookingRepository bookings, BookingCancellationService cancellations, PackBalancePort packs,
            WaitlistConsolidationPort waitlist, SingleClassChargePort charges, BookingMemberAccess census, BookingEvents events,
            BookingCounters counters, BookingAudit audit, BookingViews views, SeatHoldService seatHolds) {
        this.context = context; this.transactions = transactions; this.checks = checks; this.locks = locks; this.holds = holds; this.bookings = bookings;
        this.cancellations = cancellations; this.packs = packs; this.waitlist = waitlist; this.charges = charges; this.census = census; this.events = events;
        this.counters = counters; this.audit = audit; this.views = views; this.seatHolds = seatHolds;
    }
    /** The classes the confirmation touches (the held one first, then the swapped booking's), for the transaction lanes. */
    public List<String> classes(String seatHoldId, String swapBookingId) {
        var result = new ArrayList<String>();
        holds.findById(seatHoldId).ifPresent(h -> result.add(h.classSessionId()));
        if (swapBookingId != null) { bookings.findById(swapBookingId).ifPresent(b -> result.add(b.classSessionId())); }
        return result;
    }

    public Confirmed confirm(BookingActor actor, String seatHoldId, String swapBookingId) {
        return events.loggingBlocked("confirm", holds.findById(seatHoldId).map(SeatHold::classSessionId).orElse(null), () -> confirmInTransaction(actor, seatHoldId, swapBookingId));
    }
    private Confirmed confirmInTransaction(BookingActor actor, String seatHoldId, String swapBookingId) {
        return transactions.write(classes(seatHoldId, swapBookingId), () -> {
            var now = context.now();
            var hold = holds.findById(seatHoldId).filter(h -> Objects.equals(h.accountId(), actor.accountId()) && h.expiresAt().isAfter(now))
                    .orElseThrow(() -> new ApiException(ErrorCode.SEAT_HOLD_EXPIRED));
            locks.lock(hold.classSessionId());
            var swapTarget = swapBookingId == null ? null : bookings.findById(swapBookingId).orElse(null);
            if (swapTarget != null && !swapTarget.classSessionId().equals(hold.classSessionId())) { locks.lock(swapTarget.classSessionId()); }
            var subject = checks.subject(actor, hold.classSessionId(), hold.dogId(), now);
            checks.notBookedYet(subject);
            var limit = checks.limit(subject, now);
            Booking swapped = null;
            if (swapBookingId != null || limit.reached()) {
                if (swapBookingId == null && limit.done()) {
                    throw new ApiException(ErrorCode.BOOKING_LIMIT_REACHED, views.limitReached(limit, subject.relative(), context.weeks().nextBookableAt(subject.session().startsAt())));
                }
                if (swapTarget == null || !limit.reached() || !limit.canSwap(swapBookingId)) { throw new ApiException(ErrorCode.SWAP_NOT_ALLOWED); }
                swapped = cancellations.cancelLocked(swapTarget, actor, BookingCancelReason.SWAP, null, true);
            }
            var s = subject.session();
            int taken = bookings.forClass(s.id(), BookingRepository.LIVE).size()
                    + (int) holds.live(s.id(), now).stream().filter(h -> !h.id().equals(hold.id())).count();
            if (taken >= s.capacity()) { throw new ApiException(ErrorCode.CLASS_FULL, Map.of("heldOnly", bookings.forClass(s.id(), BookingRepository.LIVE).size() < s.capacity())); }
            var terms = seatHolds.singleClass(subject.owner().id());
            var charge = terms.map(t -> new Booking.Charge(t.mode(), t.price(), null, null, null, null)).orElse(null);
            boolean payToBook = terms.filter(t -> t.mode() == ChargeMode.PAY_TO_BOOK).isPresent();
            String id = UUID.randomUUID().toString();
            String movement = subject.pack().isPresent() ? packs.consume(subject.owner().id(), subject.dog().id(), id) : null;
            String entry = context.enabled(Module.WAITLIST) ? waitlist.consolidate(s.id(), subject.dog().id(), id).orElse(null) : null;
            String checkoutUrl = null;
            if (payToBook) {
                var checkout = charges.checkout(subject.owner().id(), subject.dog().id(), id, terms.get().price(), views.labels(s).description());
                charge = new Booking.Charge(charge.mode(), charge.price(), null, checkout.sessionId(), null, null); checkoutUrl = checkout.url();
            }
            var booking = bookings.insert(new Booking(id, TenantContext.require(), s.id(), subject.dog().id(), subject.owner().id(),
                    payToBook ? BookingState.PAYMENT_PENDING : BookingState.ACTIVE, actor.origin(), now,
                    new Booking.Actor(actor.accountId(), actor.impersonatedMemberId(), actor.impersonated() ? actor.displayName() : subject.booker().firstName()),
                    s.startsAt(), s.endsAt(), subject.week().key(), null, null, null, null, null, null,
                    swapped == null ? null : swapped.id(), null, entry, movement, null, charge, null,
                    null, now, actor.accountId(), now, actor.accountId()));
            if (swapped != null) { bookings.update(withSwapTo(swapped, id), swapped.version()); }
            holds.deleteById(hold.id());
            census.lastDogForClass(subject.booker().id(), subject.dog().id());
            if (!payToBook) { created(booking, actor); }
            events.publish(BookingEvent.Kind.SeatHoldReleased, hold.id(), SeatHoldService.payload(hold), actor);
            counters.recount(s.id(), false, actor);
            if (actor.impersonated()) { audit.createdByClub(booking); }
            return new Confirmed(booking, checkoutUrl);
        });
    }
    void created(Booking b, BookingActor actor) {
        var payload = new LinkedHashMap<String, Object>(); payload.put("bookingId", b.id()); payload.put("classId", b.classSessionId());
        payload.put("memberId", b.memberId()); payload.put("dogId", b.dogId()); payload.put("origin", b.origin());
        if (b.swapFromBookingId() != null) { payload.put("swapFromBookingId", b.swapFromBookingId()); }
        if (b.packMovementId() != null) { payload.put("packMovementId", b.packMovementId()); }
        if (b.waitlistEntryId() != null) { payload.put("waitlistEntryId", b.waitlistEntryId()); }
        events.publish(BookingEvent.Kind.BookingCreated, b.id(), payload, actor);
    }
    private static Booking withSwapTo(Booking b, String swapTo) {
        return new Booking(b.id(), b.clubId(), b.classSessionId(), b.dogId(), b.memberId(), b.state(), b.origin(), b.bookedAt(), b.bookedBy(),
                b.classStartsAt(), b.classEndsAt(), b.bookingWeekKey(), b.cancelledAt(), b.cancelledBy(), b.cancelReason(), b.cancelMessage(), b.late(),
                b.minutesBefore(), b.swapFromBookingId(), swapTo, b.waitlistEntryId(), b.packMovementId(), b.packRefundMovementId(), b.charge(),
                b.reminderSentAt(), b.version() + 1, b.createdAt(), b.createdByAccountId(), b.updatedAt(), b.updatedByAccountId());
    }

    /** S12 `UpfrontPaymentSucceeded{bookingId}` → ACTIVE + `BookingCreated` (N-04); a repeat is a no-op. */
    public void paymentSucceeded(String bookingId) {
        var initial = bookings.findById(bookingId).orElse(null);
        if (initial == null || initial.state() != BookingState.PAYMENT_PENDING) { return; }
        transactions.write(List.of(initial.classSessionId()), () -> {
            locks.lock(initial.classSessionId()); var b = bookings.require(bookingId);
            if (b.state() != BookingState.PAYMENT_PENDING) { return null; }
            // Only a PAY_TO_BOOK booking is PAYMENT_PENDING, so it always carries its charge.
            var now = context.now(); var charge = new Booking.Charge(b.charge().mode(), b.charge().price(),
                    b.charge().chargeInvoiceLineRef(), b.charge().checkoutSessionId(), b.charge().paymentIntentId(), now);
            var after = bookings.update(new Booking(b.id(), b.clubId(), b.classSessionId(), b.dogId(), b.memberId(), BookingState.ACTIVE, b.origin(),
                    b.bookedAt(), b.bookedBy(), b.classStartsAt(), b.classEndsAt(), b.bookingWeekKey(), null, null, null, null, null, null,
                    b.swapFromBookingId(), b.swapToBookingId(), b.waitlistEntryId(), b.packMovementId(), b.packRefundMovementId(), charge,
                    b.reminderSentAt(), b.version() + 1, b.createdAt(), b.createdByAccountId(), now, null), b.version());
            var actor = new BookingActor(b.bookedBy().accountId(), null, null, b.bookedBy().impersonatedMemberId(), b.origin(), ActorRole.SYSTEM);
            created(after, actor);
            counters.recount(b.classSessionId(), false, BookingActor.system());
            return null;
        });
    }
}
