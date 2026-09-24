package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.InMemoryPackBalances;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
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
 * E4-T05 registrants of the seeded classes, created through the real S08 services: each booked registrant holds a seat
 * and confirms it as its own member (`origin = APP`), with an in-memory pack opened for the `withPack` ones; the W+2
 * classes open for booking one week before they start, so the seed books them as of that opening (or the run date when
 * later). Since E5-T06 the waiting registrants join through {@link WaitlistService#join} too: R-08-12 needs a class full
 * by bookings, so a row with free seats (the D4c «4/5 + 2») briefly gets its booked count as capacity through the S06
 * class edit, the entries join, and the class gets its capacity (and capacity mode) back. A capacity raise releases no
 * seat event, so the entries stay ACTIVE, as D4c shows them. Registrant choice is unchanged (seed 42 picks the same
 * members as before); counters follow the bookings and entries.
 */
@Service
public class DemoBookingSeeder implements DemoSeedStep {
    public record Row(int week, DayOfWeek day, String start, String ring, int booked, int withPack, int waiting) {
        public Row { if (booked < 0 || waiting < 0 || withPack < 0 || withPack > booked) { throw new IllegalArgumentException("Invalid demo booking row"); } }
    }
    private final ClassSessionService sessions; private final PlanningCatalogAccess catalogs; private final ObjectMapper mapper;
    private final BookingContext context; private final ClassSessionBookingAccess classes; private final DemoMembers members;
    private final ObjectProvider<InMemoryPackBalances> packs;
    public DemoBookingSeeder(ClassSessionService sessions, PlanningCatalogAccess catalogs, ObjectMapper mapper, BookingContext context,
            ClassSessionBookingAccess classes, DemoMembers members, ObjectProvider<InMemoryPackBalances> packs) {
        this.sessions = sessions; this.catalogs = catalogs; this.mapper = mapper; this.context = context; this.classes = classes; this.members = members;
        this.packs = packs;
    }
    @Override public int order() { return 20; }
    @Override public Map<String, Integer> apply(Input input) {
        List<Row> rows = mapper.convertValue(input.specification().getOrDefault("bookings", List.of()), new TypeReference<>() { });
        var counts = new LinkedHashMap<String, Integer>(); counts.put("classBookings", 0); counts.put("classWaitlist", 0);
        if (rows.isEmpty()) { return counts; }
        var rings = catalogs.ringIdsByShortName(); var pool = members.pool(input.loginMemberIds()); var used = new HashSet<String>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var slot = sessions.slot(DemoPlanningSeeder.date(input.weekStart(), row.week(), row.day()), row.start(), DemoPlanningSeeder.require(rings, row.ring()))
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("slot", row.toString())));
            var eligible = new ArrayList<>(pool.stream().filter(c -> slot.levelIds().contains(c.levelId())).toList());
            Collections.shuffle(eligible, new Random(input.seed() * 31 + index));
            eligible.sort(Comparator.comparing(c -> used.contains(c.memberId())));
            var chosen = new ArrayList<DemoMembers.Candidate>(); var chosenMembers = new HashSet<String>();
            for (var c : eligible) { if (chosen.size() < row.booked() + row.waiting() && chosenMembers.add(c.memberId())) { chosen.add(c); } }
            if (chosen.size() < row.booked() + row.waiting()) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("slot", row.toString())); }
            var at = bookingInstant(classes.require(slot.id()).startsAt());
            for (int i = 0; i < chosen.size(); i++) {
                var c = chosen.get(i);
                if (i < row.booked()) { book(slot.id(), c, i < row.withPack(), at); counts.merge("classBookings", 1, Integer::sum); }
                used.add(c.memberId());
            }
            if (row.waiting() > 0) {
                boolean shrink = row.booked() < slot.capacity();
                if (shrink) { capacity(sessions, classes, slot.id(), row.booked()); }
                for (var c : chosen.subList(row.booked(), chosen.size())) { members.join(slot.id(), c, at); counts.merge("classWaitlist", 1, Integer::sum); }
                if (shrink) { capacity(sessions, classes, slot.id(), slot.manualCapacity() ? slot.capacity() : null); }
            }
        }
        return counts;
    }
    /** S06 class edit (the admin runs the planning steps): `null` gives the capacity back to the levels (AUTO). */
    static void capacity(ClassSessionService sessions, ClassSessionBookingAccess classes, String classId, Integer capacity) {
        var patch = new HashMap<String, Object>(); patch.put("capacity", capacity);
        sessions.patch(classId, classes.require(classId).version(), patch, false);
    }
    /** The run date when the class is already bookable, otherwise the instant its booking week opens. */
    private Instant bookingInstant(Instant classStartsAt) {
        var weeks = context.weeks(); var opens = weeks.opensAt(weeks.week(classStartsAt)); var now = context.now();
        return !now.isBefore(opens) && now.isBefore(classStartsAt) ? now : opens;
    }
    private void book(String classId, DemoMembers.Candidate c, boolean withPack, Instant at) {
        var pack = packs.getIfAvailable();
        if (withPack) {
            if (pack == null) { throw new IllegalStateException("Demo packs need the local/test pack stand-in"); }
            pack.open(c.memberId(), c.dogId(), 10, 0, null);
        }
        members.book(classId, c, at);
    }
}
