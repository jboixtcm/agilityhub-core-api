package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.ActivityMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.*;
import com.agilityhub.core.shared.application.DemoSeedStep;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** E4-T05: fictional registrants of seeded classes through the demo bookings adapter; counters follow from the bookings. */
@Service
public class DemoBookingSeeder implements DemoSeedStep {
    public record Row(int week, DayOfWeek day, String start, String ring, int booked, int withPack, int waiting) {
        public Row { if (booked < 0 || waiting < 0 || withPack < 0 || withPack > booked) { throw new IllegalArgumentException("Invalid demo booking row"); } }
    }
    private record Candidate(String memberId, String dogId, String levelId) { }
    private final ObjectProvider<DemoClassBookings> bookings; private final ClassSessionService sessions; private final PlanningCatalogAccess catalogs;
    private final ActivityMemberAccess members; private final ObjectMapper mapper;
    public DemoBookingSeeder(ObjectProvider<DemoClassBookings> bookings, ClassSessionService sessions, PlanningCatalogAccess catalogs,
            ActivityMemberAccess members, ObjectMapper mapper) {
        this.bookings = bookings; this.sessions = sessions; this.catalogs = catalogs; this.members = members; this.mapper = mapper;
    }
    @Override public int order() { return 20; }
    @Override public Map<String, Integer> apply(Input input) {
        List<Row> rows = mapper.convertValue(input.specification().getOrDefault("bookings", List.of()), new TypeReference<>() { });
        var counts = new LinkedHashMap<String, Integer>(); counts.put("classBookings", 0); counts.put("classWaitlist", 0);
        if (rows.isEmpty()) { return counts; }
        var writer = bookings.getIfAvailable();
        if (writer == null) { throw new IllegalStateException("The demo bookings adapter was replaced; seed bookings through the S08 services"); }
        var rings = catalogs.ringIdsByShortName(); var pool = candidates(input.loginMemberIds()); var used = new HashSet<String>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var slot = sessions.slot(DemoPlanningSeeder.date(input.weekStart(), row.week(), row.day()), row.start(), DemoPlanningSeeder.require(rings, row.ring()))
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("slot", row.toString())));
            var eligible = new ArrayList<>(pool.stream().filter(c -> slot.levelIds().contains(c.levelId())).toList());
            Collections.shuffle(eligible, new Random(input.seed() * 31 + index));
            eligible.sort(Comparator.comparing(c -> used.contains(c.memberId())));
            var chosen = new ArrayList<Candidate>(); var members = new HashSet<String>();
            for (var c : eligible) { if (chosen.size() < row.booked() + row.waiting() && members.add(c.memberId())) { chosen.add(c); } }
            if (chosen.size() < row.booked() + row.waiting()) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("slot", row.toString())); }
            for (int i = 0; i < chosen.size(); i++) {
                var c = chosen.get(i); boolean waitlisted = i >= row.booked();
                writer.book(slot.id(), c.memberId(), c.dogId(), i < row.withPack(), waitlisted);
                used.add(c.memberId()); counts.merge(waitlisted ? "classWaitlist" : "classBookings", 1, Integer::sum);
            }
        }
        return counts;
    }
    /** ACTIVE members with an app account (except the seed logins) and their ACTIVE dogs, in member-number order
     * (ids derive from the tenant; numbers do not, so seed 42 picks the same members in every fresh club). */
    private List<Candidate> candidates(Set<String> excluded) {
        var result = new ArrayList<Candidate>();
        for (var member : members.activeIds().stream().filter(id -> !excluded.contains(id)).map(members::member)
                .sorted(Comparator.comparing((ActivityMemberAccess.Member m) -> m.number().length()).thenComparing(ActivityMemberAccess.Member::number)).toList()) {
            if (member.accountId() == null || !member.membershipActive()) { continue; }
            member.dogs().stream().filter(d -> "ACTIVE".equals(d.status())).sorted(Comparator.comparing(ActivityMemberAccess.Dog::id))
                    .forEach(d -> result.add(new Candidate(member.id(), d.id(), d.levelId())));
        }
        return result;
    }
}
