package com.agilityhub.core.arch;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** T-15-07: the R-15-22 rule rejects the system clock and accepts reads through an injected Clock. */
class ClockRuleTest {
    static final class ReadsTheSystemClock {
        Instant now() { return Instant.now(); }
        long millis() { return System.currentTimeMillis(); }
    }
    static final class BuildsASystemClock {
        Instant utc() { return Clock.systemUTC().instant(); }
        Instant local() { return Clock.systemDefaultZone().instant(); }
        Instant zoned() { return Clock.system(ZoneId.of("Europe/Madrid")).instant(); }
        Date date() { return new Date(); }
        Date fromInstant(Instant instant) { return Date.from(instant); }
    }
    static final class ReadsTheInjectedClock {
        private final Clock clock;
        ReadsTheInjectedClock(Clock clock) { this.clock = clock; }
        LocalDate today() { return LocalDate.now(clock); }
        Instant now() { return clock.instant(); }
    }

    @Test void T_15_07_systemClockCallsOutsideTheClockBeansAreViolations() {
        var result = ArchitectureRules.TIME_FROM_CLOCK.evaluate(new ClassFileImporter().importClasses(ReadsTheSystemClock.class));
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails()).anyMatch(line -> line.contains("Instant.now()"))
                .anyMatch(line -> line.contains("System.currentTimeMillis()"));
    }

    @Test void T_15_07_systemClocksAndNewDateOutsideTheClockConfigurationAreViolations() {
        var result = ArchitectureRules.TIME_FROM_CLOCK.evaluate(new ClassFileImporter().importClasses(BuildsASystemClock.class));
        assertThat(result.hasViolation()).isTrue();
        var details = result.getFailureReport().getDetails();
        assertThat(details).anyMatch(line -> line.contains("Clock.systemUTC()"))
                .anyMatch(line -> line.contains("Clock.systemDefaultZone()"))
                .anyMatch(line -> line.contains("Clock.system(java.time.ZoneId)"))
                .anyMatch(line -> line.contains("Date.<init>()"));
        // Date.from(instant) converts a value that came from the Clock: allowed.
        assertThat(details).noneMatch(line -> line.contains("Date.from("));
    }

    @Test void T_15_07_injectedClockReadsPass() {
        assertThatCode(() -> ArchitectureRules.TIME_FROM_CLOCK.check(new ClassFileImporter().importClasses(ReadsTheInjectedClock.class)))
                .doesNotThrowAnyException();
    }
}
