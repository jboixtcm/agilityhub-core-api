package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * S08 WP-08-C waiting list. Every mutation is one Mongo transaction serialised per class by `seat_locks` (lock order
 * `seat_locks` → `class_sessions` → `waitlist_entries` / `bookings`, {@link BookingTransactions}) with the outbox inside:
 * join (R-08-12), leave (R-08-16), claim (R-08-15, the confirmation transaction of R-08-08), the `SeatReleased` and
 * `WaitlistExpired` consumers (R-08-13/14, idempotent by state: an entry is never notified twice while NOTIFIED, and
 * FIFO keeps as many open offers as free seats), and the silent cancellations S15/S13 call (`sweepStarted`,
 * `cancelByMember`). `ClassSession.counters.waiting` is recounted in each transaction that changes the live entries.
 */
@Service
public class WaitlistService {
    private final BookingContext context; private final BookingTransactions transactions; private final BookingChecks checks;
    private final SeatLockRepository locks; private final WaitlistEntryRepository waitlist; private final BookingRepository bookings;
    private final SeatHoldRepository holds; private final WaitlistTransitions transitions; private final BookingEvents events;
    private final BookingCounters counters; private final BookingMemberAccess census; private final ClassSessionBookingAccess classes;
    private final BookingViews views; private final BookingConfirmationService confirmations;
    public WaitlistService(BookingContext context, BookingTransactions transactions, BookingChecks checks, SeatLockRepository locks,
            WaitlistEntryRepository waitlist, BookingRepository bookings, SeatHoldRepository holds, WaitlistTransitions transitions, BookingEvents events,
            BookingCounters counters, BookingMemberAccess census, ClassSessionBookingAccess classes, BookingViews views,
            BookingConfirmationService confirmations) {
        this.context = context; this.transactions = transactions; this.checks = checks; this.locks = locks; this.waitlist = waitlist;
        this.bookings = bookings; this.holds = holds; this.transitions = transitions; this.events = events; this.counters = counters;
        this.census = census; this.classes = classes; this.views = views; this.confirmations = confirmations;
    }

    /**
     * R-08-12: the full eligibility chain of a booking (block, inactivity, member status, level, pack, leave date,
     * not yet open), then the class must be full by bookings alone (holds excluded), the dog has no live booking or
     * entry, and the waiting-list limits and the «can accept the seat» rule hold.
     */
    public WaitlistEntry join(BookingActor actor, String classSessionId, String dogId) {
        return events.loggingBlocked("join", classSessionId, () -> transactions.write(List.of(classSessionId), () -> {
            var now = context.now();
            locks.lock(classSessionId);
            var subject = checks.subject(actor, classSessionId, dogId, now);
            var s = subject.session();
            if (bookings.forClass(classSessionId, BookingRepository.LIVE).size() < s.capacity()) { throw new ApiException(ErrorCode.CLASS_NOT_FULL); }
            checks.notBookedYet(subject);
            if (waitlist.live(classSessionId, dogId).isPresent()) { throw new ApiException(ErrorCode.ALREADY_ON_WAITLIST); }
            var unit = context.unit();
            var limit = checks.limit(subject, now);
            int unitEntries = waitlist.liveInWeek(subject.week().key(), unit == LimitUnit.DOG ? "dogId" : "memberId",
                    unit == LimitUnit.DOG ? subject.dog().id() : subject.owner().id()).size();
            var rejection = WaitlistRules.join(new WaitlistRules.Join(waitlist.live(classSessionId).size(), context.integer("waitlist.maxPerClass"),
                    unitEntries, context.integer("waitlist.maxPerDogPerWeek"), context.integer("waitlist.maxPerDogPerWeekIfAttended"), limit));
            if (rejection.isPresent()) {
                if (rejection.get() == WaitlistRules.Rejection.BOOKING_LIMIT) {
                    throw new ApiException(ErrorCode.BOOKING_LIMIT_REACHED, views.limitReached(limit, subject.relative(), context.weeks().nextBookableAt(s.startsAt())));
                }
                throw new ApiException(ErrorCode.WAITLIST_LIMIT, Map.of("scope", rejection.get() == WaitlistRules.Rejection.CLASS ? "CLASS" : "DOG_WEEK"));
            }
            var entry = waitlist.insert(new WaitlistEntry(UUID.randomUUID().toString(), TenantContext.require(), s.id(), subject.dog().id(),
                    subject.owner().id(), actor.accountId(), now, WaitlistState.ACTIVE, waitlist.maxPosition(s.id()) + 1, null, null, null, null, null, null,
                    s.startsAt(), subject.week().key(), null, now, actor.accountId(), now, actor.accountId()));
            census.lastDogForClass(subject.booker().id(), subject.dog().id()); // R-08-23: on WaitlistJoined too
            events.publish(BookingEvent.Kind.WaitlistJoined, entry.id(), WaitlistTransitions.payload(entry), actor);
            counters.recount(s.id(), false, actor);
            return entry;
        }));
    }

    /** Member own or family group (as bookings), instructor and admin; anything else is 404. */
    public WaitlistEntry visible(String id, String actorMemberId, boolean staff) {
        var entry = waitlist.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!staff && (actorMemberId == null || !census.reachableMembers(actorMemberId).contains(entry.memberId()))) { throw new ApiException(ErrorCode.NOT_FOUND); }
        return entry;
    }
    public List<WaitlistEntry> forClass(String classSessionId) { return waitlist.forClass(classSessionId); }

    /** R-08-16 `POST /waitlist-entries/{id}/cancellation`: ACTIVE/NOTIFIED only → CANCELLED{MEMBER | ADMIN} + `WaitlistLeft`. */
    public WaitlistEntry leave(BookingActor actor, String entryId) {
        var initial = waitlist.findById(entryId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return transactions.write(List.of(initial.classSessionId()), () -> {
            var now = context.now();
            locks.lock(initial.classSessionId());
            var entry = waitlist.findById(entryId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            if (!WaitlistEntryRepository.LIVE.contains(entry.state())) { throw new ApiException(ErrorCode.WAITLIST_ENTRY_NOT_LIVE); }
            var after = transitions.cancel(entry, actor.role() == ActorRole.ADMIN ? WaitlistCancelReason.ADMIN : WaitlistCancelReason.MEMBER, now, actor.accountId());
            events.publish(BookingEvent.Kind.WaitlistLeft, entry.id(), WaitlistTransitions.payload(entry), actor);
            counters.recount(entry.classSessionId(), false, actor);
            return after;
        });
    }

    /** The classes a claim touches, for the transaction lanes (the entry's, then a swapped booking's). */
    public List<String> classes(String entryId, String swapBookingId) {
        var result = new ArrayList<String>();
        waitlist.findById(entryId).ifPresent(e -> result.add(e.classSessionId()));
        if (swapBookingId != null) { bookings.findById(swapBookingId).ifPresent(b -> result.add(b.classSessionId())); }
        return result;
    }
    /**
     * R-08-15 `POST /waitlist-entries/{id}/claim`: the entry is NOTIFIED (FIFO: `confirmBy > now`), the hold is the
     * caller's, live and taken for this entry; then the very same R-08-08 confirmation (checks, swap, BR-01, capacity)
     * runs in this transaction and consolidates the entry; in ALL_AT_ONCE the last seat demotes the other NOTIFIED ones.
     */
    public BookingConfirmationService.Confirmed claim(BookingActor actor, String entryId, String seatHoldId, String swapBookingId) {
        var initial = waitlist.findById(entryId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return events.loggingBlocked("claim", initial.classSessionId(), () -> transactions.write(classes(entryId, swapBookingId), () -> {
            var now = context.now();
            locks.lock(initial.classSessionId());
            var entry = waitlist.findById(entryId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            switch (entry.state()) {
                case NOTIFIED -> { if (entry.confirmBy() != null && !entry.confirmBy().isAfter(now)) { throw new ApiException(ErrorCode.WAITLIST_OFFER_EXPIRED); } }
                case EXPIRED -> throw new ApiException(ErrorCode.WAITLIST_OFFER_EXPIRED);
                case ACTIVE -> throw new ApiException(entry.notifiedAt() != null && full(entry, now) ? ErrorCode.SEAT_TAKEN : ErrorCode.WAITLIST_NOT_NOTIFIED);
                default -> throw new ApiException(ErrorCode.WAITLIST_NOT_NOTIFIED);
            }
            var hold = holds.findById(seatHoldId).filter(h -> Objects.equals(h.accountId(), actor.accountId()) && h.expiresAt().isAfter(now))
                    .orElseThrow(() -> new ApiException(ErrorCode.SEAT_HOLD_EXPIRED));
            if (!entryId.equals(hold.waitlistEntryId())) { throw new ApiException(ErrorCode.WAITLIST_NOT_NOTIFIED); }
            return confirmations.confirm(actor, seatHoldId, swapBookingId);
        }));
    }

    /**
     * The hold's rule for a demoted entry ({@link SeatHoldService}): the class is full when its live bookings plus the
     * other dogs' live holds reach the capacity. Only then was the offer taken (SEAT_TAKEN); a seat freed later without a
     * new offer leaves the entry simply not notified (E5-T08).
     */
    private boolean full(WaitlistEntry entry, Instant now) {
        int capacity = classes.find(entry.classSessionId()).filter(ClassSessionBookingAccess.Session::active).map(ClassSessionBookingAccess.Session::capacity).orElse(0);
        long others = holds.live(entry.classSessionId(), now).stream().filter(h -> !h.dogId().equals(entry.dogId())).count();
        return bookings.forClass(entry.classSessionId(), BookingRepository.LIVE).size() + others >= capacity;
    }
    /** `SeatReleased{notifyWaitlist = true}` consumer (R-08-13/14): never more offers than seats the event released and still free. */
    public int offerSeats(String classSessionId, int freeSeats) { return offer(classSessionId, freeSeats, null); }
    /**
     * `WaitlistExpired` consumer (FIFO, R-08-14, S15 R-15-16): the next entries while seats and time remain. The expiry
     * itself is S15 P6 (E5-T05); defensively, the named entry is expired here if it is still NOTIFIED past `confirmBy`.
     * A second delivery finds as many open offers as free seats and offers nothing (T-08-34).
     */
    public int offerNext(String classSessionId, String expiredEntryId) { return offer(classSessionId, Integer.MAX_VALUE, expiredEntryId); }
    private int offer(String classSessionId, int seats, String expiredEntryId) {
        if (!context.enabled(Module.WAITLIST)) { return 0; }
        return transactions.write(List.of(classSessionId), () -> {
            var now = context.now();
            locks.lock(classSessionId);
            if (expiredEntryId != null) {
                waitlist.findById(expiredEntryId).filter(e -> e.state() == WaitlistState.NOTIFIED && e.confirmBy() != null && !e.confirmBy().isAfter(now))
                        .ifPresent(e -> transitions.expire(e, now));
            }
            var session = classes.find(classSessionId).filter(ClassSessionBookingAccess.Session::active).orElse(null);
            if (session == null || !WaitlistRules.inTime(now, session.startsAt(), context.integer("waitlist.notifyThresholdMinutes"))) { return 0; }
            if (expiredEntryId != null && context.waitlistMode() != WaitlistMode.FIFO) { return 0; }
            var live = waitlist.live(classSessionId);
            var waiting = live.stream().filter(e -> e.state() == WaitlistState.ACTIVE).toList();
            int open = (int) live.stream().filter(e -> e.state() == WaitlistState.NOTIFIED).count();
            var mode = context.waitlistMode();
            int count = WaitlistRules.toNotify(mode, Math.min(seats, transitions.freeSeats(classSessionId)), open, waiting.size());
            if (count == 0) { return 0; }
            var confirmBy = mode == WaitlistMode.FIFO ? WaitlistRules.confirmBy(now, context.integer("waitlist.fifoConfirmMinutes"), session.startsAt()) : null;
            var actor = BookingActor.system();
            var notified = waiting.subList(0, count).stream().map(e -> transitions.notify(e, now, confirmBy, null)).toList();
            if (mode == WaitlistMode.ALL_AT_ONCE) { notified(classSessionId, notified.stream().map(WaitlistEntry::id).toList(), null, mode, actor); }
            else { notified.forEach(e -> notified(classSessionId, List.of(e.id()), confirmBy, mode, actor)); } // one confirmBy per entry (T-08-45)
            return count;
        });
    }
    private void notified(String classSessionId, List<String> entryIds, Instant confirmBy, WaitlistMode mode, BookingActor actor) {
        var payload = new LinkedHashMap<String, Object>(); payload.put("entryIds", entryIds); payload.put("classId", classSessionId);
        if (confirmBy != null) { payload.put("confirmBy", confirmBy); }
        payload.put("mode", mode);
        events.publish(BookingEvent.Kind.WaitlistNotified, classSessionId, payload, actor);
    }

    /** R-08-16 `CLASS_STARTED` (S15 P8, R-15-18a — scheduled by E6): live entries of started classes, silently. */
    public int sweepStarted(Instant now) {
        var byClass = waitlist.liveStartedBy(now).stream().collect(Collectors.groupingBy(WaitlistEntry::classSessionId, LinkedHashMap::new, Collectors.toList()));
        int count = 0;
        for (var group : byClass.entrySet()) {
            count += transactions.write(List.of(group.getKey()), () -> cancelLocked(group.getKey(), group.getValue(), WaitlistCancelReason.CLASS_STARTED, now));
        }
        return count;
    }
    /** S15 P5c / S13 leave handling, inside the caller's transaction when there is one: every live entry of the member's dogs, silently. */
    public int cancelByMember(String memberId, WaitlistCancelReason reason) {
        var live = waitlist.liveForMember(memberId);
        var byClass = live.stream().collect(Collectors.groupingBy(WaitlistEntry::classSessionId, LinkedHashMap::new, Collectors.toList()));
        return transactions.write(List.copyOf(byClass.keySet()), () -> {
            int count = 0; var now = context.now();
            for (var group : byClass.entrySet()) { count += cancelLocked(group.getKey(), group.getValue(), reason, now); }
            return count;
        });
    }
    private int cancelLocked(String classSessionId, List<WaitlistEntry> entries, WaitlistCancelReason reason, Instant now) {
        locks.lock(classSessionId);
        int count = 0;
        for (var initial : entries) {
            var e = waitlist.findById(initial.id()).orElse(null);
            if (e == null || !WaitlistEntryRepository.LIVE.contains(e.state())) { continue; }
            transitions.cancel(e, reason, now, null); count++;
        }
        if (count > 0) { counters.recount(classSessionId, false, BookingActor.system()); }
        return count;
    }
}
