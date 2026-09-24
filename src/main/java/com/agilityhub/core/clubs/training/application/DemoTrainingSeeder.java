package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.bookings.application.BookingContext;
import com.agilityhub.core.clubs.bookings.application.DemoScenarioSeeder;
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
 * E5-T06 demo scenario, S09 half (`scenario.training`, applied with {@link DemoSeedStep.Input#scenario()}): the
 * `Dog.freeTrainingOverride` cases (S03 `DogService.free`) and members' free-training bookings through
 * {@link TrainingBookingService#book} «as of» `trainingAt` (the R-09-05 window and counter follow that instant). The
 * scenario's instructor ring reservations are S06's (`DemoPlanningSeeder`).
 */
@Service
public class DemoTrainingSeeder implements DemoSeedStep {
    public record FreeTrainingOverride(int member, String dog, Boolean freeTraining) { }
    public record Booking(int member, String dog, DemoScenarioSeeder.At trainingAt, DayOfWeek day, String start, String ring) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spec(List<FreeTrainingOverride> overrides, List<Booking> bookings) {
        public Spec { overrides = overrides == null ? List.of() : List.copyOf(overrides); bookings = bookings == null ? List.of() : List.copyOf(bookings); }
    }
    private final TrainingBookingService training; private final TrainingMemberAccess census; private final DogService dogs;
    private final PlanningCatalogAccess catalogs; private final BookingContext time; private final TrainingContext context; private final ObjectMapper mapper;
    public DemoTrainingSeeder(TrainingBookingService training, TrainingMemberAccess census, DogService dogs, PlanningCatalogAccess catalogs,
            BookingContext time, TrainingContext context, ObjectMapper mapper) {
        this.training = training; this.census = census; this.dogs = dogs; this.catalogs = catalogs; this.time = time; this.context = context; this.mapper = mapper;
    }
    @Override public int order() { return 40; }
    @Override public Map<String, Integer> apply(Input input) {
        var counts = new LinkedHashMap<String, Integer>(); counts.put("scenarioTrainingOverrides", 0); counts.put("scenarioTrainingBookings", 0);
        var section = input.scenario().get("training");
        if (section == null) { return counts; }
        var spec = mapper.convertValue(section, Spec.class);
        var levels = catalogs.levelIdsByCode(); var rings = catalogs.ringIdsByShortName(); var zone = context.zone(); var weekStart = input.weekStart();
        for (var o : spec.overrides()) {
            dogs.free(dog(input.member(o.member()), DemoPlanningSeeder.require(levels, o.dog())), o.freeTraining());
            counts.merge("scenarioTrainingOverrides", 1, Integer::sum);
        }
        for (var b : spec.bookings()) {
            String memberId = input.member(b.member());
            var member = census.member(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("demoMember", memberId)));
            var actor = new TrainingActor(member.accountId(), memberId, member.firstName(), null, TrainingOrigin.APP, TrainingCancelledBy.MEMBER);
            var startsAt = LocalDateTime.of(DemoScenarioSeeder.At.date(weekStart, b.day()), LocalTime.parse(b.start())).atZone(zone).toInstant();
            String dogId = dog(memberId, DemoPlanningSeeder.require(levels, b.dog()));
            DemoSeedActor.as(member.accountId(), "MEMBER", () -> time.asOf(b.trainingAt().instant(weekStart, zone),
                    () -> training.book(actor, dogId, startsAt, DemoPlanningSeeder.require(rings, b.ring()), null, UUID.randomUUID().toString())));
            counts.merge("scenarioTrainingBookings", 1, Integer::sum);
        }
        return counts;
    }
    /** The member's own first ACTIVE dog (by id) of a level. */
    private String dog(String memberId, String levelId) {
        return census.reachableDogs(memberId).stream().map(Map.Entry::getKey)
                .filter(d -> d.memberId().equals(memberId) && d.active() && levelId.equals(d.levelId())).map(TrainingMemberAccess.Dog::id).sorted().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("demoMember", memberId, "levelId", levelId)));
    }
}
