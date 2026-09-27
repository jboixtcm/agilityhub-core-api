package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.bookings.application.BookingContext;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.DogService;
import com.agilityhub.core.clubs.census.application.TrainingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.DemoPlanningSeeder;
import com.agilityhub.core.clubs.training.domain.TrainingCancelledBy;
import com.agilityhub.core.clubs.training.domain.TrainingOrigin;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * E6-T04 demo free-training history of S10 WP-10-G (`scenario.attendance.training`, applied with
 * {@link DemoSeedStep.Input#scenario()}): the holder's dog gets free training by override (S03 `DogService.free`; its
 * level grants none) and books each past session of `sessions` through {@link TrainingBookingService#book} as the member,
 * «as of» `bookedDaysBefore` days earlier at the same hour (R-09-05's window and weekly count follow that instant). The
 * sessions give 22/D13's `trainingsPerWeek` (R-10-08: 10 in the 30 days → 2,3) and 25's training rows.
 */
@Service
public class DemoTrainingHistorySeeder implements DemoSeedStep {
    public record Cast(String name, int member, String dog) { }
    public record Session(int week, DayOfWeek day) { }
    public record Training(String by, String ring, String start, int bookedDaysBefore, List<Session> sessions) {
        public Training { sessions = sessions == null ? List.of() : List.copyOf(sessions); if (bookedDaysBefore < 0) { throw new IllegalArgumentException("bookedDaysBefore"); } }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spec(List<Cast> cast, Training training) {
        public Spec { cast = cast == null ? List.of() : List.copyOf(cast); }
    }
    private final TrainingBookingService training; private final TrainingMemberAccess census; private final DogService dogs; private final PlanningCatalogAccess catalogs;
    private final BookingContext time; private final TrainingContext context; private final ObjectMapper mapper;
    public DemoTrainingHistorySeeder(TrainingBookingService training, TrainingMemberAccess census, DogService dogs, PlanningCatalogAccess catalogs, BookingContext time,
            TrainingContext context, ObjectMapper mapper) {
        this.training = training; this.census = census; this.dogs = dogs; this.catalogs = catalogs; this.time = time; this.context = context; this.mapper = mapper;
    }
    @Override public int order() { return 41; }
    @Override public Map<String, Integer> apply(Input input) {
        var counts = new LinkedHashMap<String, Integer>(); counts.put("trainingHistoryOverrides", 0); counts.put("trainingHistoryBookings", 0);
        var section = input.scenario().get("attendance");
        if (section == null) { return counts; }
        var spec = mapper.convertValue(section, Spec.class);
        if (spec.training() == null) { return counts; }
        var who = spec.cast().stream().filter(c -> c.name().equals(spec.training().by())).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("demoCast", spec.training().by())));
        String memberId = input.member(who.member());
        var member = census.member(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("demoMember", memberId)));
        var dog = census.reachableDogs(memberId).stream().map(Map.Entry::getKey).filter(d -> d.memberId().equals(memberId) && d.active() && who.dog().equals(d.name()))
                .min(Comparator.comparing(TrainingMemberAccess.Dog::id)).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("demoCast", who.name())));
        if (!Boolean.TRUE.equals(dog.freeTrainingOverride())) { dogs.free(dog.id(), true); counts.merge("trainingHistoryOverrides", 1, Integer::sum); }
        var actor = new TrainingActor(member.accountId(), memberId, member.firstName(), null, TrainingOrigin.APP, TrainingCancelledBy.MEMBER);
        String ringId = DemoPlanningSeeder.require(catalogs.ringIdsByShortName(), spec.training().ring()); var zone = context.zone();
        for (var session : spec.training().sessions()) {
            if (session.week() >= 0) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "scenario.attendance.training.sessions.week")); }
            var startsAt = LocalDateTime.of(DemoPlanningSeeder.date(input.weekStart(), session.week(), session.day()), LocalTime.parse(spec.training().start())).atZone(zone);
            var bookedAt = startsAt.minusDays(spec.training().bookedDaysBefore()).toInstant();
            DemoSeedActor.as(member.accountId(), "MEMBER", () -> time.asOf(bookedAt,
                    () -> training.book(actor, dog.id(), startsAt.toInstant(), ringId, null, UUID.randomUUID().toString())));
            counts.merge("trainingHistoryBookings", 1, Integer::sum);
        }
        return counts;
    }
}
