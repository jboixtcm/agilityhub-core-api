package com.agilityhub.core.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import java.util.List;
import java.util.Optional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

final class ArchitectureRules {

    static final String BASE_PACKAGE = "com.agilityhub.core.";
    static final List<String> CONTEXTS = List.of(
            "shared", "platform", "identity", "clubs.census", "clubs.catalogs", "clubs.content", "clubs.signup",
            "clubs.scheduling", "clubs.activities", "clubs.bookings", "clubs.training",
            "clubs.followup", "clubs.messaging", "clubs.dashboard", "clubs.common", "courses", "payments", "migration");

    static final ArchRule DOMAIN_INDEPENDENCE = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.data..",
                    "org.springframework.security..", "jakarta.servlet..")
            // The skeleton has no executable domain classes yet.
            .allowEmptyShould(true);

    // A (*) slice would merge all clubs.* packages. Flatten these sub-contexts
    // into individual slices so a census <-> bookings cycle cannot go unnoticed.
    static final ArchRule CONTEXT_CYCLES = slices().assignedFrom(new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            return contextOf(javaClass).map(SliceIdentifier::of).orElse(SliceIdentifier.ignore());
        }

        @Override
        public String getDescription() {
            return "the bounded contexts, with separate clubs sub-contexts";
        }
    }).should().beFreeOfCycles();

    static final ArchRule CONTEXT_BOUNDARIES = classes().should(new ArchCondition<>(
            "access other contexts only through their application packages") {
        @Override
        public void check(JavaClass source, ConditionEvents events) {
            contextOf(source).ifPresent(sourceContext -> source.getDirectDependenciesFromSelf()
                    .forEach(dependency -> contextOf(dependency.getTargetClass()).ifPresent(targetContext -> {
                        if (!sourceContext.equals(targetContext)
                                && !sharedContract(dependency.getTargetClass())
                                && !inPackage(dependency.getTargetClass(),
                                        BASE_PACKAGE + targetContext + ".application")) {
                            events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                        }
                    })));
        }
    });

    static final ArchRule SHARED_INDEPENDENCE = noClasses().that()
            .resideInAPackage(BASE_PACKAGE + "shared..")
            .should().dependOnClassesThat(new DescribedPredicate<>("belong to another context") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return contextOf(javaClass).filter(context -> !context.equals("shared")).isPresent();
                }
            });

    /** S15 R-15-22: only the Clock/ClubClock beans read the system time; everything else receives the injected Clock. */
    static final List<String> CLOCK_BEANS = List.of(BASE_PACKAGE + "configuration.ClockConfiguration",
            BASE_PACKAGE + "shared.application.DefaultClubClock", BASE_PACKAGE + "shared.application.OffsetClock");
    static final ArchRule NOW_CALLS = noClasses()
            .that(outside(CLOCK_BEANS, "are not the Clock/ClubClock beans"))
            .should().callMethod(java.time.Instant.class, "now")
            .orShould().callMethod(java.time.LocalDate.class, "now")
            .orShould().callMethod(java.time.LocalDateTime.class, "now")
            .orShould().callMethod(java.time.ZonedDateTime.class, "now")
            .orShould().callMethod(java.time.OffsetDateTime.class, "now")
            .orShould().callMethod(java.time.LocalTime.class, "now")
            // E5-T10: the now(ZoneId) overloads read the system clock too; only now(Clock) is allowed.
            .orShould().callMethod(java.time.LocalDate.class, "now", java.time.ZoneId.class)
            .orShould().callMethod(java.time.LocalDateTime.class, "now", java.time.ZoneId.class)
            .orShould().callMethod(java.time.ZonedDateTime.class, "now", java.time.ZoneId.class)
            .orShould().callMethod(java.time.OffsetDateTime.class, "now", java.time.ZoneId.class)
            .orShould().callMethod(java.time.LocalTime.class, "now", java.time.ZoneId.class)
            .orShould().callMethod(System.class, "currentTimeMillis")
            .because("S15 R-15-22: time comes from the injected Clock (MutableClock in tests)");
    /** Only the clock configuration builds a system clock; `new Date()` and `Calendar.getInstance()` read the system time too. */
    static final ArchRule SYSTEM_CLOCKS = noClasses()
            .that(outside(List.of(BASE_PACKAGE + "configuration.ClockConfiguration"), "are not the clock configuration"))
            .should().callMethod(java.time.Clock.class, "systemUTC")
            .orShould().callMethod(java.time.Clock.class, "systemDefaultZone")
            .orShould().callMethod(java.time.Clock.class, "system", java.time.ZoneId.class)
            .orShould().callConstructor(java.util.Date.class)
            .orShould().callMethod(java.util.Calendar.class, "getInstance")
            .because("S15 R-15-22: the Clock bean is the only system clock");
    static final ArchRule TIME_FROM_CLOCK = CompositeArchRule.of(NOW_CALLS).and(SYSTEM_CLOCKS);

    /** E4-T05 review #2: the demo seed actor opens an authenticated context for any account; only `Demo*` seed classes may use it. */
    static final ArchRule DEMO_SEED_ACTOR = noClasses()
            .that(new DescribedPredicate<>("are not Demo* seed classes") {
                @Override public boolean test(JavaClass type) { return !topLevel(type).getSimpleName().startsWith("Demo"); }
            })
            .should().accessClassesThat().haveFullyQualifiedName(BASE_PACKAGE + "shared.application.DemoSeedActor")
            .because("E4-T05: DemoSeedActor skips the role checks of a real request, so only the local/test demo seed may run as a seed account");

    /** E4-T05 review #8: the class counters are written only by the S08 booking writers (inside their seat-lock transaction). */
    static final ArchRule COUNTER_WRITERS = noClasses()
            .that().resideOutsideOfPackages(BASE_PACKAGE + "clubs.bookings..", BASE_PACKAGE + "clubs.scheduling..")
            .should().callMethod(BASE_PACKAGE + "clubs.scheduling.application.ClassSessionBookingAccess", "counters", "java.lang.String", "int", "int",
                    BASE_PACKAGE + "clubs.scheduling.application.ClassSessionBookingAccess$LowAlert")
            .because("E4-T05: ClassSession.counters follow the S08 bookings and waiting-list entries; nothing else sets them");

    /**
     * E5-T06 review #8: `BookingContext.asOf` moves the business time of S08 and S09 (`TrainingContext.now()` reads it) on the
     * calling thread. Only the demo seed books «as of» a scenario instant; no request path may move the time it is judged at.
     */
    static final ArchRule BOOKING_TIME_OVERRIDE = noClasses()
            .that(new DescribedPredicate<>("are not Demo* seed classes") {
                @Override public boolean test(JavaClass type) { return !topLevel(type).getSimpleName().startsWith("Demo"); }
            })
            .should().callMethod(BASE_PACKAGE + "clubs.bookings.application.BookingContext", "asOf", "java.time.Instant", "java.util.function.Supplier")
            .because("E5-T06: the «as of» business time is a demo-seed device; the API and the jobs always judge at the injected Clock");

    /** E5-T05 review #10: a consumer's read-only envelope of another context's event can never be published. */
    static final ArchRule CONSUMER_ENVELOPES = noClasses()
            .that().haveSimpleNameEndingWith("ForeignEvent")
            .should().beAssignableTo(BASE_PACKAGE + "shared.domain.DomainEvent")
            .because("E5-T09: EventPublisher.publish takes a DomainEvent; a consumer envelope is not one");

    /**
     * E6-T01: S10 splits into `clubs.bookings` (attendance) and `clubs.followup` (tasks, D14). Follow-up depends on
     * bookings; what the sheet and the card need from it goes through `clubs.bookings.application.ports.DogFollowupPort`.
     */
    static final ArchRule BOOKINGS_WITHOUT_FOLLOWUP = noClasses()
            .that().resideInAPackage(BASE_PACKAGE + "clubs.bookings..")
            .should().dependOnClassesThat().resideInAPackage(BASE_PACKAGE + "clubs.followup..")
            .because("E6-T01: clubs.followup → clubs.bookings.application, never the other way round");

    /** E6-T01 (ADR-001): the platform (jobs framework, configuration of clubs) knows no club context. */
    static final ArchRule PLATFORM_WITHOUT_CLUBS = noClasses()
            .that().resideInAPackage(BASE_PACKAGE + "platform..")
            .should().dependOnClassesThat().resideInAPackage(BASE_PACKAGE + "clubs..")
            .because("E6-T01: owning contexts register their Job beans; the platform never imports them");

    private static JavaClass topLevel(JavaClass type) {
        var current = type;
        while (current.getEnclosingClass().isPresent()) { current = current.getEnclosingClass().get(); }
        return current;
    }

    private static DescribedPredicate<JavaClass> outside(List<String> allowed, String description) {
        return new DescribedPredicate<>(description) {
            @Override public boolean test(JavaClass type) {
                return allowed.stream().noneMatch(bean -> type.getName().equals(bean) || type.getName().startsWith(bean + "$"));
            }
        };
    }

    private static boolean sharedContract(JavaClass type) {
        return inPackage(type, BASE_PACKAGE + "shared.domain")
                || type.getName().equals(BASE_PACKAGE + "shared.persistence.TenantRepository")
                || type.getName().equals(BASE_PACKAGE + "shared.persistence.GlobalRepository");
    }

    private ArchitectureRules() {
    }

    private static Optional<String> contextOf(JavaClass javaClass) {
        return CONTEXTS.stream().filter(context -> inPackage(javaClass, BASE_PACKAGE + context)).findFirst();
    }

    private static boolean inPackage(JavaClass javaClass, String packageName) {
        return javaClass.getPackageName().equals(packageName)
                || javaClass.getPackageName().startsWith(packageName + ".");
    }
}
