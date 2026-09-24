package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.census.application.ActivityMemberAccess;
import com.agilityhub.core.shared.application.DemoSeedActor;
import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * The demo seed's S08 actors (E4-T05 rows, E5-T06 scenario): members with a dog, acting as themselves through the real
 * hold, confirmation, cancellation and waiting-list services «as of» a seed instant ({@link BookingContext#asOf}).
 */
@Service
public class DemoMembers {
    public record Candidate(String memberId, String accountId, String firstName, String dogId, String levelId) {
        BookingActor actor() { return BookingActor.member(accountId, memberId, firstName); }
    }
    private final ActivityMemberAccess members; private final BookingContext context; private final SeatHoldService holds;
    private final BookingConfirmationService confirmations; private final BookingCancellationService cancellations; private final WaitlistService waitlist;
    public DemoMembers(ActivityMemberAccess members, BookingContext context, SeatHoldService holds, BookingConfirmationService confirmations,
            BookingCancellationService cancellations, WaitlistService waitlist) {
        this.members = members; this.context = context; this.holds = holds; this.confirmations = confirmations; this.cancellations = cancellations;
        this.waitlist = waitlist;
    }

    /** ACTIVE members with an app account (except the seed logins) and their ACTIVE dogs, in member-number order
     * (ids derive from the tenant; numbers do not, so seed 42 picks the same members in every fresh club). */
    public List<Candidate> pool(Set<String> excluded) {
        var result = new ArrayList<Candidate>();
        for (var member : members.activeIds().stream().filter(id -> !excluded.contains(id)).map(members::member)
                .sorted(Comparator.comparing((ActivityMemberAccess.Member m) -> m.number().length()).thenComparing(ActivityMemberAccess.Member::number)).toList()) {
            if (member.accountId() == null || !member.membershipActive()) { continue; }
            String firstName = member.name().split(" ")[0];
            member.dogs().stream().filter(d -> "ACTIVE".equals(d.status())).sorted(Comparator.comparing(ActivityMemberAccess.Dog::id))
                    .forEach(d -> result.add(new Candidate(member.id(), member.accountId(), firstName, d.id(), d.levelId())));
        }
        return result;
    }
    /** A given member's first ACTIVE dog (by id) of a level. */
    public Candidate member(String memberId, String levelId) {
        var member = members.member(memberId);
        var dog = member.dogs().stream().filter(d -> "ACTIVE".equals(d.status()) && Objects.equals(levelId, d.levelId()))
                .min(Comparator.comparing(ActivityMemberAccess.Dog::id))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("demoMember", memberId, "levelId", String.valueOf(levelId))));
        return new Candidate(member.id(), member.accountId(), member.name().split(" ")[0], dog.id(), dog.levelId());
    }
    public Booking book(String classId, Candidate c, Instant at) {
        var actor = c.actor();
        return DemoSeedActor.as(c.accountId(), "MEMBER", () -> context.asOf(at, () -> {
            var held = holds.hold(actor, classId, c.dogId(), null);
            return confirmations.confirm(actor, held.hold().id(), null).booking();
        }));
    }
    public WaitlistEntry join(String classId, Candidate c, Instant at) {
        return DemoSeedActor.as(c.accountId(), "MEMBER", () -> context.asOf(at, () -> waitlist.join(c.actor(), classId, c.dogId())));
    }
    public Booking cancel(String bookingId, Candidate c, Instant at) {
        return DemoSeedActor.as(c.accountId(), "MEMBER", () -> context.asOf(at, () -> cancellations.cancel(bookingId, c.actor(), null)));
    }
}
