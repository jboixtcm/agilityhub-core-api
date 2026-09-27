package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.scheduling.application.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * E6-T04 demo data of S10 WP-10-G, attendance half (`scenario.attendance` of `seeds/demo-*.yaml`, applied with
 * {@link DemoSeedStep.Input#scenario()}; the cast is named by the census step before it), through the real services as
 * each actor and «as of» each instant:
 * <ul>
 * <li>past classes (`history`, weeks before 0; created and validated by the S06 step on the sheet class's ring, hour, levels and
 * instructor) booked by their cast at the opening of their booking week (S08), then marked through S10 by the instructor
 * (NOTIFIED at `noticeAt`, before the class and in time; PRESENT/NO_SHOW at `markedAt`), each NO_SHOW taken by the P3 claim
 * of the next day's `messaging.noShowNoticeTime` (its N-19 follows through the outbox), one member's late cancellation and one
 * class the club cancels (S06);</li>
 * <li>the sheet class of week 0 (R-10-02's example): its cast booked at the opening of its booking week and the waiting one
 * joined (the class briefly held at its booked count, as E4's rows do: R-08-12 needs a class full by bookings).</li>
 * </ul>
 * The tasks, the observation and the free-training history are the follow-up and S09 steps'.
 */
@Service
public class DemoAttendanceSeeder implements DemoSeedStep {
    public record Cast(String name, int member, String dog, String level) { }
    public record Sheet(DayOfWeek day, String start, String ring, List<String> booked, List<String> waiting) {
        public Sheet { booked = booked == null ? List.of() : List.copyOf(booked); waiting = waiting == null ? List.of() : List.copyOf(waiting); }
    }
    public record Moment(String who, String at) { }
    public record ClubCancellation(String at, String text) { }
    public record PastClass(int week, DayOfWeek day, List<String> booked, Map<String, AttendanceState> marks, Moment lateCancellation,
            ClubCancellation cancelledByClub) {
        public PastClass {
            if (week >= 0) { throw new IllegalArgumentException("Demo history classes are in past weeks"); }
            marks = marks == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(marks));
            var names = new LinkedHashSet<String>(booked == null ? List.of() : booked); names.addAll(marks.keySet());
            booked = List.copyOf(names);
        }
    }
    public record History(String end, String markedAt, String noticeAt, List<PastClass> classes) {
        public History { classes = classes == null ? List.of() : List.copyOf(classes); }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spec(List<Cast> cast, Sheet sheet, History history) {
        public Spec { cast = cast == null ? List.of() : List.copyOf(cast); }
    }
    static final List<String> COUNTS = List.of("attendanceBookings", "attendanceWaitlist", "attendanceMarks", "attendanceCancellations", "attendanceNoShowBatches");

    private final BookingContext context; private final DemoMembers members; private final ClassSessionService sessions; private final ClassSessionBookingAccess classes;
    private final ClassCancellationUseCase cancellations; private final InstructorScheduleAccess schedule; private final AttendanceSheetService sheets;
    private final AttendanceCallers callers; private final NoShowNoticeClaims claims; private final PlanningCatalogAccess catalogs;
    private final com.agilityhub.core.clubs.census.application.BookingMemberAccess census; private final ObjectMapper mapper;
    public DemoAttendanceSeeder(BookingContext context, DemoMembers members, ClassSessionService sessions, ClassSessionBookingAccess classes,
            ClassCancellationUseCase cancellations, InstructorScheduleAccess schedule, AttendanceSheetService sheets, AttendanceCallers callers,
            NoShowNoticeClaims claims, PlanningCatalogAccess catalogs, com.agilityhub.core.clubs.census.application.BookingMemberAccess census, ObjectMapper mapper) {
        this.context = context; this.members = members; this.sessions = sessions; this.classes = classes; this.cancellations = cancellations;
        this.schedule = schedule; this.sheets = sheets; this.callers = callers; this.claims = claims; this.catalogs = catalogs; this.census = census; this.mapper = mapper;
    }
    @Override public int order() { return 37; }

    private record Staff(String instructorId, String memberId, String accountId) { }

    @Override public Map<String, Integer> apply(Input input) {
        var counts = new LinkedHashMap<String, Integer>(); COUNTS.forEach(key -> counts.put(key, 0));
        var section = input.scenario().get("attendance");
        if (section == null) { return counts; }
        var spec = mapper.convertValue(section, Spec.class);
        var rings = catalogs.ringIdsByShortName(); var levels = catalogs.levelIdsByCode(); var weekStart = input.weekStart();
        var sheet = sessions.slot(DemoPlanningSeeder.date(weekStart, 0, spec.sheet().day()), spec.sheet().start(), DemoPlanningSeeder.require(rings, spec.sheet().ring()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("slot", spec.sheet().day() + " " + spec.sheet().start())));
        if (!"ACTIVE".equals(sheet.state())) { throw new ApiException(ErrorCode.INVALID_STATE, Map.of("slot", spec.sheet().day() + " " + spec.sheet().start())); }
        var model = classes.require(sheet.id());
        String instructorId = model.instructorIds().getFirst(); String instructorMember = catalogs.instructorMembers(List.of(instructorId)).getFirst();
        var staff = new Staff(instructorId, instructorMember, census.member(instructorMember).map(com.agilityhub.core.clubs.census.application.BookingMemberAccess.Member::accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("instructor", instructorId))));
        var cast = new LinkedHashMap<String, DemoMembers.Candidate>();
        for (var c : spec.cast()) { cast.put(c.name(), members.member(input.member(c.member()), DemoPlanningSeeder.require(levels, c.level()))); }
        var sequence = new int[] {0};
        history(spec, input, cast, model, staff, counts, sequence);
        // The sheet class of week 0, at the opening of its booking week; the waiting ones need it full by bookings (R-08-12).
        Instant opening = opening(model.startsAt(), sequence);
        for (String name : spec.sheet().booked()) {
            members.book(sheet.id(), person(cast, name), opening.plusSeconds(sequence[0]++)); counts.merge("attendanceBookings", 1, Integer::sum);
        }
        if (!spec.sheet().waiting().isEmpty()) {
            boolean shrink = spec.sheet().booked().size() < sheet.capacity();
            if (shrink) { DemoBookingSeeder.capacity(sessions, classes, sheet.id(), spec.sheet().booked().size()); }
            for (String name : spec.sheet().waiting()) {
                members.join(sheet.id(), person(cast, name), opening.plusSeconds(sequence[0]++)); counts.merge("attendanceWaitlist", 1, Integer::sum);
            }
            if (shrink) { DemoBookingSeeder.capacity(sessions, classes, sheet.id(), sheet.manualCapacity() ? sheet.capacity() : null); }
        }
        return counts;
    }

    private void history(Spec spec, Input input, Map<String, DemoMembers.Candidate> cast, ClassSessionBookingAccess.Session model, Staff staff,
            Map<String, Integer> counts, int[] sequence) {
        var zone = context.zone(); var history = spec.history(); var weekStart = input.weekStart();
        var ordered = history.classes().stream().sorted(Comparator.comparing((PastClass p) -> DemoPlanningSeeder.date(weekStart, p.week(), p.day()))).toList();
        var noticeTime = LocalTime.parse(context.config().get("messaging.noShowNoticeTime", String.class));
        for (var past : ordered) {
            var date = DemoPlanningSeeder.date(weekStart, past.week(), past.day());
            // Created and validated by the S06 step (DemoPlanningSeeder) on the sheet class's ring and hour.
            String classId = sessions.slot(date, spec.sheet().start(), model.ringId()).filter(s -> "ACTIVE".equals(s.state()))
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("slot", date + " " + spec.sheet().start()))).id();
            Instant opening = opening(classes.require(classId).startsAt(), sequence);
            var booked = new LinkedHashMap<String, Booking>();
            for (String name : past.booked()) {
                booked.put(name, members.book(classId, person(cast, name), opening.plusSeconds(sequence[0]++))); counts.merge("attendanceBookings", 1, Integer::sum);
            }
            if (past.cancelledByClub() != null) {
                context.asOf(local(date, past.cancelledByClub().at(), zone), () -> {
                    cancellations.cancelByClub(classId, past.cancelledByClub().text(), input.adminAccountId()); return null;
                });
                counts.merge("attendanceCancellations", 1, Integer::sum);
                continue;
            }
            if (past.lateCancellation() != null) {
                String who = past.lateCancellation().who();
                members.cancel(Objects.requireNonNull(booked.get(who), who).id(), person(cast, who), local(date, past.lateCancellation().at(), zone));
                counts.merge("attendanceCancellations", 1, Integer::sum);
            }
            // «Ha avisat» before the class (R-10-05, in time), then the other marks once it is over (R-10-03 window).
            var notified = items(past, List.of(AttendanceState.NOTIFIED), booked);
            if (!notified.isEmpty()) { mark(classId, notified, local(date, history.noticeAt(), zone), staff); }
            var rest = items(past, List.of(AttendanceState.PRESENT, AttendanceState.NO_SHOW), booked);
            if (!rest.isEmpty()) { mark(classId, rest, local(date, history.markedAt(), zone), staff); }
            counts.merge("attendanceMarks", notified.size() + rest.size(), Integer::sum);
            if (past.marks().containsValue(AttendanceState.NO_SHOW)) {
                // R-10-06: the P3 batch of the next morning takes it; its N-19 follows through the outbox.
                var next = date.plusDays(1);
                context.asOf(next.atTime(noticeTime).atZone(zone).toInstant(), () -> claims.claim(next));
                counts.merge("attendanceNoShowBatches", 1, Integer::sum);
            }
        }
    }
    private static List<AttendanceSheetService.Item> items(PastClass past, List<AttendanceState> states, Map<String, Booking> booked) {
        var items = new ArrayList<AttendanceSheetService.Item>();
        for (var state : states) {
            past.marks().forEach((name, marked) -> {
                if (marked == state) { items.add(new AttendanceSheetService.Item(Objects.requireNonNull(booked.get(name), name).id(), state)); }
            });
        }
        return items;
    }
    private void mark(String classId, List<AttendanceSheetService.Item> items, Instant at, Staff staff) {
        DemoSeedActor.as(staff.accountId(), "INSTRUCTOR", () -> context.asOf(at, () -> {
            var caller = callers.resolve(staff.accountId(), staff.memberId(), staff.instructorId(), false);
            long version = schedule.find(classId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).summary().version();
            return sheets.save(classId, version, items, caller);
        }));
    }
    private static DemoMembers.Candidate person(Map<String, DemoMembers.Candidate> cast, String name) {
        var person = cast.get(name);
        if (person == null) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("demoCast", name)); }
        return person;
    }
    /** The opening of the booking week holding {@code startsAt} (R-08-01: from then on the class is in W0), one second per action. */
    private Instant opening(Instant startsAt, int[] sequence) { return context.weeks().week(startsAt).start().plusSeconds(++sequence[0]); }
    private static Instant local(LocalDate date, String time, ZoneId zone) { return date.atTime(LocalTime.parse(time)).atZone(zone).toInstant(); }
}
