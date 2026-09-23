package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.InMemoryPackBalances;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.ActivityMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * E4-T05 registrants of the seeded classes, since E5-T02 created through the real S08 services: each booked registrant
 * holds a seat and confirms it as its own member (`origin = APP`), with an in-memory pack opened for the `withPack`
 * ones; the W+2 classes open for booking one week before they start, so the seed books them as of that opening
 * (or the run date when later). Waiting registrants become ACTIVE waiting-list entries until E5-T03 ships the join
 * service. Registrant choice is unchanged (seed 42 picks the same members as before); counters follow the bookings.
 */
@Service
public class DemoBookingSeeder implements DemoSeedStep {
    public record Row(int week, DayOfWeek day, String start, String ring, int booked, int withPack, int waiting) {
        public Row { if (booked < 0 || waiting < 0 || withPack < 0 || withPack > booked) { throw new IllegalArgumentException("Invalid demo booking row"); } }
    }
    private record Candidate(String memberId, String accountId, String firstName, String dogId, String levelId) { }
    private final ClassSessionService sessions; private final PlanningCatalogAccess catalogs; private final ActivityMemberAccess members;
    private final ObjectMapper mapper; private final SeatHoldService holds; private final BookingConfirmationService confirmations;
    private final BookingContext context; private final ClassSessionBookingAccess classes; private final WaitlistEntryRepository waitlist;
    private final BookingCounters counters; private final ObjectProvider<InMemoryPackBalances> packs;
    public DemoBookingSeeder(ClassSessionService sessions, PlanningCatalogAccess catalogs, ActivityMemberAccess members, ObjectMapper mapper,
            SeatHoldService holds, BookingConfirmationService confirmations, BookingContext context, ClassSessionBookingAccess classes,
            WaitlistEntryRepository waitlist, BookingCounters counters, ObjectProvider<InMemoryPackBalances> packs) {
        this.sessions = sessions; this.catalogs = catalogs; this.members = members; this.mapper = mapper; this.holds = holds; this.confirmations = confirmations;
        this.context = context; this.classes = classes; this.waitlist = waitlist; this.counters = counters; this.packs = packs;
    }
    @Override public int order() { return 20; }
    @Override public Map<String, Integer> apply(Input input) {
        List<Row> rows = mapper.convertValue(input.specification().getOrDefault("bookings", List.of()), new TypeReference<>() { });
        var counts = new LinkedHashMap<String, Integer>(); counts.put("classBookings", 0); counts.put("classWaitlist", 0);
        if (rows.isEmpty()) { return counts; }
        var rings = catalogs.ringIdsByShortName(); var pool = candidates(input.loginMemberIds()); var used = new HashSet<String>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var slot = sessions.slot(DemoPlanningSeeder.date(input.weekStart(), row.week(), row.day()), row.start(), DemoPlanningSeeder.require(rings, row.ring()))
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("slot", row.toString())));
            var eligible = new ArrayList<>(pool.stream().filter(c -> slot.levelIds().contains(c.levelId())).toList());
            Collections.shuffle(eligible, new Random(input.seed() * 31 + index));
            eligible.sort(Comparator.comparing(c -> used.contains(c.memberId())));
            var chosen = new ArrayList<Candidate>(); var chosenMembers = new HashSet<String>();
            for (var c : eligible) { if (chosen.size() < row.booked() + row.waiting() && chosenMembers.add(c.memberId())) { chosen.add(c); } }
            if (chosen.size() < row.booked() + row.waiting()) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("slot", row.toString())); }
            var session = classes.require(slot.id()); var at = bookingInstant(session.startsAt());
            for (int i = 0; i < chosen.size(); i++) {
                var c = chosen.get(i);
                if (i < row.booked()) { book(slot.id(), c, i < row.withPack(), at); counts.merge("classBookings", 1, Integer::sum); }
                else { wait(session, c, i - row.booked() + 1, at); counts.merge("classWaitlist", 1, Integer::sum); }
                used.add(c.memberId());
            }
            if (row.waiting() > 0) { counters.recount(slot.id(), false, BookingActor.system()); }
        }
        return counts;
    }
    /** The run date when the class is already bookable, otherwise the instant its booking week opens. */
    private Instant bookingInstant(Instant classStartsAt) {
        var weeks = context.weeks(); var opens = weeks.opensAt(weeks.week(classStartsAt)); var now = context.now();
        return !now.isBefore(opens) && now.isBefore(classStartsAt) ? now : opens;
    }
    private void book(String classId, Candidate c, boolean withPack, Instant at) {
        var pack = packs.getIfAvailable();
        if (withPack) {
            if (pack == null) { throw new IllegalStateException("Demo packs need the local/test pack stand-in"); }
            pack.open(c.memberId(), c.dogId(), 10, 0, null);
        }
        var actor = new BookingActor(c.accountId(), c.memberId(), c.firstName(), null, com.agilityhub.core.clubs.bookings.domain.BookingOrigin.APP,
                com.agilityhub.core.clubs.bookings.domain.ActorRole.MEMBER);
        DemoSeedActor.as(c.accountId(), "MEMBER", () -> context.asOf(at, () -> {
            var held = holds.hold(actor, classId, c.dogId(), null);
            return confirmations.confirm(actor, held.hold().id(), null);
        }));
    }
    private void wait(ClassSessionBookingAccess.Session session, Candidate c, int position, Instant at) {
        var key = context.weeks().week(session.startsAt()).key();
        waitlist.insert(new WaitlistEntry(UUID.randomUUID().toString(), TenantContext.require(), session.id(), c.dogId(), c.memberId(), c.accountId(), at,
                WaitlistState.ACTIVE, position, null, null, null, null, null, session.startsAt(), key, null, at, c.accountId(), at, c.accountId()));
    }
    /** ACTIVE members with an app account (except the seed logins) and their ACTIVE dogs, in member-number order
     * (ids derive from the tenant; numbers do not, so seed 42 picks the same members in every fresh club). */
    private List<Candidate> candidates(Set<String> excluded) {
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
}
