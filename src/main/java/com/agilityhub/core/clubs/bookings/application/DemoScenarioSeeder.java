package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.WaitlistRules;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.MemberService;
import com.agilityhub.core.clubs.scheduling.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * E5-T06 demo scenario, S08 half (`scenario` section of `seeds/demo-*.yaml`, applied only when the anchor week is still
 * ahead, see {@link DemoSeedStep.Input#scenario()}): login members' class bookings and cancellations, class rows (non-login
 * registrants up to capacity or a count, waiting entries, the P2 exemption), login members' waiting-list entries and
 * booking blocks — through the S08 services as each member «as of» a scenario instant, and the S03/S06 services for blocks
 * and exemptions. The actions run as one timeline in instant order (file order on ties): by default an action happens
 * at the opening of its class's booking week (R-08-01: from then on it is W0), one second after the previous one; a
 * booking can name its own `at`, a cancellation its `cancelAt`. Each cancellation is followed by the waiting-list offer
 * its `SeatReleased` asks for (R-08-11/13/14), as the outbox consumer would do; the consumer then finds it done.
 * Non-login registrants come from {@link DemoMembers#pool}, each dog at most once so no weekly limit interferes.
 */
@Service
public class DemoScenarioSeeder implements DemoSeedStep {
    /** A scenario instant: a club-local time on a day of week `week` (0 = the anchor week, -1 = the one before; absent = 0). */
    public record At(Integer week, DayOfWeek day, String time) {
        public Instant instant(LocalDate weekStart, ZoneId zone) {
            return LocalDateTime.of(DemoPlanningSeeder.date(weekStart, week == null ? 0 : week, day), LocalTime.parse(time)).atZone(zone).toInstant();
        }
        public static LocalDate date(LocalDate weekStart, DayOfWeek day) { return DemoPlanningSeeder.date(weekStart, 0, day); }
    }
    public record ClassBooking(int member, String dog, DayOfWeek day, String start, String ring, At at, At cancelAt) { }
    public record ClassRow(DayOfWeek day, String start, String ring, boolean fill, int booked, int waiting, boolean riskExempt) {
        public ClassRow { if (booked < 0 || waiting < 0) { throw new IllegalArgumentException("Invalid demo scenario class"); } }
    }
    public record Join(int member, String dog, DayOfWeek day, String start, String ring) { }
    public record Block(int member, String reason) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spec(At demoNow, List<ClassBooking> classBookings, List<ClassRow> classes, List<Join> waitlist, List<Block> bookingBlocks) {
        public Spec {
            classBookings = classBookings == null ? List.of() : List.copyOf(classBookings); classes = classes == null ? List.of() : List.copyOf(classes);
            waitlist = waitlist == null ? List.of() : List.copyOf(waitlist); bookingBlocks = bookingBlocks == null ? List.of() : List.copyOf(bookingBlocks);
        }
    }
    static final List<String> COUNTS = List.of("scenarioClassBookings", "scenarioCancellations", "scenarioWaitlist", "scenarioOffers", "scenarioRiskExempt",
            "scenarioBookingBlocks");
    private record Action(Instant at, int order, Runnable run) { }
    private final ClassSessionService sessions; private final ClassSessionBookingAccess classes; private final PlanningCatalogAccess catalogs;
    private final BookingContext context; private final DemoMembers members; private final WaitlistService waitlist; private final BookingRepository bookings;
    private final WaitlistEntryRepository entries; private final MemberService census; private final ObjectMapper mapper;
    public DemoScenarioSeeder(ClassSessionService sessions, ClassSessionBookingAccess classes, PlanningCatalogAccess catalogs, BookingContext context,
            DemoMembers members, WaitlistService waitlist, BookingRepository bookings, WaitlistEntryRepository entries, MemberService census, ObjectMapper mapper) {
        this.sessions = sessions; this.classes = classes; this.catalogs = catalogs; this.context = context; this.members = members; this.waitlist = waitlist;
        this.bookings = bookings; this.entries = entries; this.census = census; this.mapper = mapper;
    }
    @Override public int order() { return 35; }
    @Override public Map<String, Integer> apply(Input input) {
        var counts = new LinkedHashMap<String, Integer>(); COUNTS.forEach(key -> counts.put(key, 0));
        var scenario = input.scenario();
        if (scenario.isEmpty()) { return counts; }
        var spec = mapper.convertValue(scenario, Spec.class);
        var rings = catalogs.ringIdsByShortName(); var levels = catalogs.levelIdsByCode(); var zone = context.zone(); var weekStart = input.weekStart();
        var actions = new ArrayList<Action>(); var sequence = new int[] {0};
        for (var item : spec.classBookings()) {
            var slot = slot(weekStart, item.day(), item.start(), DemoPlanningSeeder.require(rings, item.ring()));
            var member = members.member(input.member(item.member()), DemoPlanningSeeder.require(levels, item.dog()));
            var booked = new Booking[1];
            add(actions, item.at() == null ? opening(slot.id(), sequence) : item.at().instant(weekStart, zone), at -> {
                booked[0] = members.book(slot.id(), member, at); counts.merge("scenarioClassBookings", 1, Integer::sum);
            });
            if (item.cancelAt() != null) {
                add(actions, item.cancelAt().instant(weekStart, zone), at -> {
                    var cancelled = members.cancel(booked[0].id(), member, at);
                    counts.merge("scenarioCancellations", 1, Integer::sum); counts.merge("scenarioOffers", offer(cancelled, at), Integer::sum);
                });
            }
        }
        var pool = members.pool(input.loginMemberIds()); var usedDogs = new HashSet<String>();
        for (int index = 0; index < spec.classes().size(); index++) {
            var row = spec.classes().get(index); long random = input.seed() * 37 + index;
            var slot = slot(weekStart, row.day(), row.start(), DemoPlanningSeeder.require(rings, row.ring()));
            add(actions, opening(slot.id(), sequence), at -> {
                // `fill` books what is left of the capacity at this point of the timeline.
                int booked = row.fill() ? Math.max(0, classes.require(slot.id()).capacity() - bookings.forClass(slot.id(), BookingRepository.LIVE).size()) : row.booked();
                var eligible = new ArrayList<>(pool.stream().filter(c -> slot.levelIds().contains(c.levelId()) && !usedDogs.contains(c.dogId())).toList());
                Collections.shuffle(eligible, new Random(random));
                var chosen = new ArrayList<DemoMembers.Candidate>(); var chosenMembers = new HashSet<String>();
                for (var c : eligible) { if (chosen.size() < booked + row.waiting() && chosenMembers.add(c.memberId())) { chosen.add(c); } }
                if (chosen.size() < booked + row.waiting()) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("scenarioClass", row.toString())); }
                for (int i = 0; i < chosen.size(); i++) {
                    var c = chosen.get(i); usedDogs.add(c.dogId());
                    if (i < booked) { members.book(slot.id(), c, at.plusMillis(i)); counts.merge("scenarioClassBookings", 1, Integer::sum); }
                    else { members.join(slot.id(), c, at.plusMillis(i)); counts.merge("scenarioWaitlist", 1, Integer::sum); }
                }
                if (row.riskExempt()) { sessions.exemption(slot.id(), true); counts.merge("scenarioRiskExempt", 1, Integer::sum); }
            });
        }
        for (var item : spec.waitlist()) {
            var slot = slot(weekStart, item.day(), item.start(), DemoPlanningSeeder.require(rings, item.ring()));
            var member = members.member(input.member(item.member()), DemoPlanningSeeder.require(levels, item.dog()));
            add(actions, opening(slot.id(), sequence), at -> { members.join(slot.id(), member, at); counts.merge("scenarioWaitlist", 1, Integer::sum); });
        }
        actions.sort(Comparator.comparing(Action::at).thenComparingInt(Action::order));
        actions.forEach(a -> a.run().run());
        for (var block : spec.bookingBlocks()) { census.block(input.member(block.member()), block.reason()); counts.merge("scenarioBookingBlocks", 1, Integer::sum); }
        return counts;
    }
    private static void add(List<Action> actions, Instant at, java.util.function.Consumer<Instant> work) {
        actions.add(new Action(at, actions.size(), () -> work.accept(at)));
    }
    /** R-08-11 notifyWaitlist of the released seat, then the consumer's `offerSeats` at the same instant. */
    private int offer(Booking cancelled, Instant at) {
        if (!context.enabled(Module.WAITLIST) || !WaitlistRules.inTime(at, cancelled.classStartsAt(), context.integer("waitlist.notifyThresholdMinutes"))) { return 0; }
        var session = classes.require(cancelled.classSessionId());
        int free = session.capacity() - bookings.forClass(session.id(), BookingRepository.LIVE).size();
        boolean waiting = entries.live(session.id()).stream().anyMatch(e -> e.state() == WaitlistState.ACTIVE);
        return free > 0 && waiting ? context.asOf(at, () -> waitlist.offerSeats(session.id(), free)) : 0;
    }
    /** The opening of the class's booking week (R-08-01: from then on it is W0), one second apart per scenario action. */
    private Instant opening(String classId, int[] sequence) {
        return context.weeks().week(classes.require(classId).startsAt()).start().plusSeconds(++sequence[0]);
    }
    private ClassSessionService.Slot slot(LocalDate weekStart, DayOfWeek day, String start, String ringId) {
        var slot = sessions.slot(At.date(weekStart, day), start, ringId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("slot", day + " " + start)));
        if (!"ACTIVE".equals(slot.state())) { throw new ApiException(ErrorCode.INVALID_STATE, Map.of("slot", day + " " + start)); }
        return slot;
    }
}
