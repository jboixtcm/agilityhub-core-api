package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.WaitlistConsolidationPort;
import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * The S08 §5 waiting-list transitions that run inside another operation's transaction (the caller already holds the
 * class's seat lock, or — `cancelAll` — the S06 transaction that holds the class): consolidation on a booking
 * (R-08-08/R-08-15), demotion of NOTIFIED entries when the seat is gone (R-08-13, ALL_AT_ONCE) and the silent
 * cancellations of R-08-16. Entries are never deleted (BR-12); every write is a compare-and-set on `version`.
 */
@Service
public class WaitlistTransitions implements WaitlistConsolidationPort {
    private final BookingContext context; private final WaitlistEntryRepository waitlist; private final BookingRepository bookings;
    private final ClassSessionBookingAccess classes; private final BookingEvents events;
    public WaitlistTransitions(BookingContext context, WaitlistEntryRepository waitlist, BookingRepository bookings, ClassSessionBookingAccess classes,
            BookingEvents events) {
        this.context = context; this.waitlist = waitlist; this.bookings = bookings; this.classes = classes; this.events = events;
    }

    @Override
    public Optional<String> consolidate(String classSessionId, String dogId, String bookingId, boolean claim, BookingActor actor) {
        var entry = waitlist.live(classSessionId, dogId).orElse(null);
        if (entry == null) { return Optional.empty(); }
        var now = context.now();
        // A direct booking records BOOKED_DIRECTLY for the trail; the state is CONSOLIDATED either way (R-08-16).
        write(entry, WaitlistState.CONSOLIDATED, entry.notifiedAt(), entry.confirmBy(), bookingId, null,
                claim ? null : WaitlistCancelReason.BOOKED_DIRECTLY, now, actor.accountId());
        var payload = new LinkedHashMap<String, Object>(); payload.put("entryId", entry.id()); payload.put("bookingId", bookingId);
        events.publish(BookingEvent.Kind.WaitlistConsolidated, entry.id(), payload, actor);
        return Optional.of(entry.id());
    }

    @Override
    public List<String> demoteIfFull(String classSessionId, BookingActor actor) {
        if (!context.enabled(Module.WAITLIST) || context.waitlistMode() != WaitlistMode.ALL_AT_ONCE || freeSeats(classSessionId) > 0) { return List.of(); }
        var now = context.now(); var demoted = new ArrayList<String>();
        for (var e : waitlist.live(classSessionId)) {
            if (e.state() != WaitlistState.NOTIFIED) { continue; }
            // `notifiedAt` stays for the record: an ACTIVE entry with notifiedAt is one whose offer was taken (N-46).
            write(e, WaitlistState.ACTIVE, e.notifiedAt(), null, null, null, null, now, actor.accountId());
            demoted.add(e.id());
        }
        return demoted;
    }
    /** Seats of the class not taken by live (ACTIVE + PAYMENT_PENDING) bookings; 0 when the class is gone or not ACTIVE. */
    int freeSeats(String classSessionId) {
        var session = classes.find(classSessionId).filter(ClassSessionBookingAccess.Session::active).orElse(null);
        return session == null ? 0 : Math.max(0, session.capacity() - bookings.forClass(classSessionId, BookingRepository.LIVE).size());
    }

    /**
     * R-08-16 `CLASS_CANCELLED`: S06 (`ClassCancellationUseCase`, through `cancelByClub`) inside its transaction; no
     * event of its own (N-08a notifies, `waitlistIds[]` of `ClassCancelledByClub`). Counters are set by the caller.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<WaitlistEntry> cancelAll(String classSessionId, String actorAccountId) {
        var now = context.now(); var cancelled = new ArrayList<WaitlistEntry>();
        for (var e : waitlist.live(classSessionId)) { cancelled.add(cancel(e, WaitlistCancelReason.CLASS_CANCELLED, now, actorAccountId)); }
        return cancelled;
    }
    WaitlistEntry cancel(WaitlistEntry e, WaitlistCancelReason reason, Instant now, String actorAccountId) {
        return write(e, WaitlistState.CANCELLED, e.notifiedAt(), e.confirmBy(), e.bookingId(), now, reason, now, actorAccountId);
    }
    WaitlistEntry notify(WaitlistEntry e, Instant now, Instant confirmBy, String actorAccountId) {
        return write(e, WaitlistState.NOTIFIED, now, confirmBy, null, null, null, now, actorAccountId);
    }
    WaitlistEntry expire(WaitlistEntry e, Instant now) { return write(e, WaitlistState.EXPIRED, e.notifiedAt(), e.confirmBy(), null, null, null, now, null); }

    private WaitlistEntry write(WaitlistEntry e, WaitlistState state, Instant notifiedAt, Instant confirmBy, String bookingId, Instant cancelledAt,
            WaitlistCancelReason reason, Instant now, String actorAccountId) {
        long version = e.version() == null ? 0L : e.version();
        return waitlist.update(new WaitlistEntry(e.id(), e.clubId(), e.classSessionId(), e.dogId(), e.memberId(), e.accountId(), e.joinedAt(), state,
                e.position(), notifiedAt, confirmBy, bookingId, cancelledAt, reason, e.classStartsAt(), e.bookingWeekKey(), version + 1,
                e.createdAt(), e.createdByAccountId(), now, actorAccountId), version);
    }
    static Map<String, Object> payload(WaitlistEntry e) {
        var payload = new LinkedHashMap<String, Object>(); payload.put("entryId", e.id()); payload.put("classId", e.classSessionId());
        payload.put("memberId", e.memberId()); payload.put("dogId", e.dogId()); return payload;
    }
}
