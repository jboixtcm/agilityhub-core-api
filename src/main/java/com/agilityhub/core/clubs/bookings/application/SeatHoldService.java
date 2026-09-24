package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * S08 R-08-07 `SeatHold`: one Mongo transaction serialised by `seat_locks` — lock → eligibility → limit →
 * `taken = ACTIVE|PAYMENT_PENDING bookings + live holds of other dogs` (always `expiresAt > now`) → upsert the dog's
 * hold (re-entering refreshes it) → `SeatHeld`. A live hold guarantees the seat; TTL expiry emits nothing.
 */
@Service
public class SeatHoldService {
    public record Held(SeatHold hold, BookingChecks.Subject subject, BookingLimits.Result limit, Optional<SingleClassChargePort.Terms> payment,
            Instant now, int holdSeconds) { }
    private final BookingContext context; private final BookingTransactions transactions; private final BookingChecks checks;
    private final SeatLockRepository locks; private final SeatHoldRepository holds; private final BookingRepository bookings;
    private final WaitlistEntryRepository waitlist; private final BookingEvents events; private final SingleClassChargePort charges; private final BookingViews views;
    public SeatHoldService(BookingContext context, BookingTransactions transactions, BookingChecks checks, SeatLockRepository locks, SeatHoldRepository holds,
            BookingRepository bookings, WaitlistEntryRepository waitlist, BookingEvents events, SingleClassChargePort charges, BookingViews views) {
        this.context = context; this.transactions = transactions; this.checks = checks; this.locks = locks; this.holds = holds; this.bookings = bookings;
        this.waitlist = waitlist; this.events = events; this.charges = charges; this.views = views;
    }

    /**
     * A plain hold first runs {@link #admit} once without the lock: the rejections it finds (CLASS_FULL,
     * BOOKING_LIMIT_REACHED, the eligibility codes) are the ones the locked path would give at that instant. So at an
     * opening peak the members who cannot get a seat never queue behind the class's lane (E5-T06 k6: 300 members, 10
     * classes of 5 seats). A hold that looks possible still takes the lock and the transaction, which decide again from
     * scratch: the no-overbooking guarantee stays with R-08-07. A claim's hold (waiting-list offer) always goes straight
     * to the locked path. A pre-check `CLASS_FULL{heldOnly: true}` (the free seats are only held by other dogs) is not
     * answered from outside the lock: those holds may expire in the next milliseconds, so the locked path decides it with
     * its own `now` (E5-T06 review #9).
     */
    public Held hold(BookingActor actor, String classSessionId, String dogId, String waitlistEntryId) {
        return events.loggingBlocked("hold", classSessionId, () -> {
            if (waitlistEntryId == null) { precheck(actor, classSessionId, dogId); }
            return transactions.write(List.of(classSessionId), () -> {
                var now = context.now();
                locks.lock(classSessionId);
                var admitted = admit(actor, classSessionId, dogId, waitlistEntryId, now);
                var subject = admitted.subject(); var limit = admitted.limit();
                int seconds = context.integer("bookings.seatHoldSeconds");
                var hold = holds.upsert(new SeatHold(UUID.randomUUID().toString(), TenantContext.require(), classSessionId, dogId, subject.owner().id(),
                        actor.accountId(), waitlistEntryId, now, now.plusSeconds(seconds)));
                events.publish(BookingEvent.Kind.SeatHeld, hold.id(), payload(hold), actor);
                return new Held(hold, subject, limit, singleClass(subject.owner().id()), now, seconds);
            });
        });
    }
    private void precheck(BookingActor actor, String classSessionId, String dogId) {
        try { admit(actor, classSessionId, dogId, null, context.now()); }
        catch (ApiException rejected) {
            if (rejected.code() != ErrorCode.CLASS_FULL || !Boolean.TRUE.equals(rejected.details().get("heldOnly"))) { throw rejected; }
        }
    }
    private record Admitted(BookingChecks.Subject subject, BookingLimits.Result limit) { }
    /** The ordered R-08-04…07/09/15 checks of a hold at `now`; throws the first rejection, reads only. */
    private Admitted admit(BookingActor actor, String classSessionId, String dogId, String waitlistEntryId, Instant now) {
        var subject = checks.subject(actor, classSessionId, dogId, now);
        checks.notBookedYet(subject);
        int active = bookings.forClass(classSessionId, BookingRepository.LIVE).size();
        long others = holds.live(classSessionId, now).stream().filter(h -> !h.dogId().equals(dogId)).count();
        boolean full = active + others >= subject.session().capacity();
        if (waitlistEntryId != null) { offer(waitlistEntryId, classSessionId, dogId, now, full); }
        var limit = checks.limit(subject, now);
        if (limit.done()) {
            throw new ApiException(ErrorCode.BOOKING_LIMIT_REACHED, views.limitReached(limit, subject.relative(), context.weeks().nextBookableAt(subject.session().startsAt())));
        }
        if (full) {
            if (waitlistEntryId != null) { throw new ApiException(ErrorCode.SEAT_TAKEN); }
            throw new ApiException(ErrorCode.CLASS_FULL, Map.of("heldOnly", active < subject.session().capacity()));
        }
        return new Admitted(subject, limit);
    }
    /** `DELETE /seat-holds/{id}`: 204 also when the hold is gone; only its owner account releases it. */
    public void release(BookingActor actor, String seatHoldId) {
        var existing = holds.findById(seatHoldId).orElse(null);
        if (existing == null) { return; }
        transactions.write(List.of(existing.classSessionId()), () -> {
            var hold = holds.findById(seatHoldId).orElse(null);
            if (hold == null || !Objects.equals(hold.accountId(), actor.accountId())) { return null; }
            holds.deleteById(hold.id());
            events.publish(BookingEvent.Kind.SeatHoldReleased, hold.id(), payload(hold), actor);
            return null;
        });
    }
    /** Catalog payload of `SeatHeld` / `SeatHoldReleased`; the aggregate id is the hold id. */
    static Map<String, Object> payload(SeatHold hold) {
        var payload = new LinkedHashMap<String, Object>(); payload.put("classId", hold.classSessionId()); payload.put("memberId", hold.memberId());
        payload.put("dogId", hold.dogId()); payload.put("expiresAt", hold.expiresAt()); return payload;
    }
    /**
     * R-08-15 hold of a claim: the entry must be the dog's, NOTIFIED and (FIFO) still inside `confirmBy`. An ALL_AT_ONCE
     * entry demoted because somebody else took the seat (ACTIVE with `notifiedAt`) answers SEAT_TAKEN while the class
     * stays full, and a FIFO offer that already expired answers WAITLIST_OFFER_EXPIRED. The claim is {@link WaitlistService#claim}.
     */
    private void offer(String entryId, String classSessionId, String dogId, Instant now, boolean full) {
        var entry = waitlist.findById(entryId).filter(e -> e.classSessionId().equals(classSessionId) && e.dogId().equals(dogId))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        switch (entry.state()) {
            case NOTIFIED -> { if (entry.confirmBy() != null && !entry.confirmBy().isAfter(now)) { throw new ApiException(ErrorCode.WAITLIST_OFFER_EXPIRED); } }
            case EXPIRED -> throw new ApiException(ErrorCode.WAITLIST_OFFER_EXPIRED);
            case ACTIVE -> throw new ApiException(entry.notifiedAt() != null && full ? ErrorCode.SEAT_TAKEN : ErrorCode.WAITLIST_NOT_NOTIFIED);
            default -> throw new ApiException(ErrorCode.WAITLIST_NOT_NOTIFIED);
        }
    }
    Optional<SingleClassChargePort.Terms> singleClass(String ownerMemberId) {
        return context.enabled(Module.SINGLE_CLASS) ? charges.terms(ownerMemberId) : Optional.empty();
    }
}
