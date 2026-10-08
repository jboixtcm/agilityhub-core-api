package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.catalogs.application.*;
import com.agilityhub.core.clubs.scheduling.application.*;
import com.agilityhub.core.shared.application.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.stereotype.Service;

/** E8 demo history: real classes, bookings and attendance under the plan's historical attendance billing terms. */
@Service
public class DemoBillingBookingsSeeder {
    public record Spec(int member, int weeksAgo, DayOfWeek day, String start, String end, String ring, String level, int instructor) { }
    private final PlanningCatalogAccess catalogs; private final DemoBillingPlanTerms plans; private final BillingCensusAccess census;
    private final ClassSessionService sessions; private final WeekValidationUseCase validation; private final WeekGenerationUseCase weeks; private final DemoMembers members;
    private final BookingContext context; private final AttendanceSheetService attendance; private final PendingChargeConsumers charges;
    public DemoBillingBookingsSeeder(PlanningCatalogAccess catalogs, DemoBillingPlanTerms plans, BillingCensusAccess census, ClassSessionService sessions,
            WeekValidationUseCase validation, WeekGenerationUseCase weeks, DemoMembers members, BookingContext context, AttendanceSheetService attendance, PendingChargeConsumers charges) {
        this.catalogs=catalogs; this.plans=plans; this.census=census; this.sessions=sessions; this.validation=validation;
        this.weeks=weeks; this.members=members; this.context=context; this.attendance=attendance; this.charges=charges;
    }
    public void seed(DemoSeedStep.Input input, Spec spec) {
        if (spec.weeksAgo() < 1) { throw new IllegalArgumentException("Demo billed classes must be in past weeks"); }
        String memberId = input.member(spec.member());
        plans.attendance(census.member(memberId).orElseThrow().planId(), () -> {
            var day = input.today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(spec.weeksAgo()).with(spec.day());
            String level = DemoPlanningSeeder.require(catalogs.levelIdsByCode(), spec.level());
            String ring = DemoPlanningSeeder.require(catalogs.ringIdsByShortName(), spec.ring());
            String week = weeks.create(day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).week().id();
            sessions.create(day, spec.start(), spec.end(), ring,
                    List.of(level), List.of(catalogs.activeInstructorIds().get(spec.instructor())), 1, null, false);
            if (sessions.hasDrafts(week)) { validation.validate(week); }
            var cls = sessions.slot(day, spec.start(), ring).orElseThrow();
            var start = day.atTime(LocalTime.parse(spec.start())).atZone(context.zone()).toInstant();
            var booking = members.book(cls.id(), members.member(memberId, level), context.weeks().week(start).start().plusSeconds(1));
            var markedAt = day.atTime(LocalTime.parse(spec.end())).atZone(context.zone()).toInstant().plusSeconds(60);
            context.asOf(markedAt, () -> attendance.save(cls.id(), 0, List.of(new AttendanceSheetService.Item(booking.id(), AttendanceState.PRESENT)),
                    new AttendanceCaller(input.adminAccountId(), "Demo admin", true, null)));
            // The seed needs the charge immediately. The real outbox delivery repeats this idempotent consumer later.
            charges.attendanceMarked(booking.id(), "PRESENT", "PENDING");
        });
    }
}
