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
            .orShould().callMethod(System.class, "currentTimeMillis")
            .because("S15 R-15-22: time comes from the injected Clock (MutableClock in tests)");
    /** Only the clock configuration builds a system clock; `new Date()` reads the system time too. */
    static final ArchRule SYSTEM_CLOCKS = noClasses()
            .that(outside(List.of(BASE_PACKAGE + "configuration.ClockConfiguration"), "are not the clock configuration"))
            .should().callMethod(java.time.Clock.class, "systemUTC")
            .orShould().callMethod(java.time.Clock.class, "systemDefaultZone")
            .orShould().callMethod(java.time.Clock.class, "system", java.time.ZoneId.class)
            .orShould().callConstructor(java.util.Date.class)
            .because("S15 R-15-22: the Clock bean is the only system clock");
    static final ArchRule TIME_FROM_CLOCK = CompositeArchRule.of(NOW_CALLS).and(SYSTEM_CLOCKS);

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
