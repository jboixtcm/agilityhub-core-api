package com.agilityhub.core.arch;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** T-15-07: the R-15-22 rule rejects the system clock and accepts reads through an injected Clock. */
class ClockRuleTest {
    static final class ReadsTheSystemClock {
        Instant now() { return Instant.now(); }
        long millis() { return System.currentTimeMillis(); }
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

    @Test void T_15_07_injectedClockReadsPass() {
        assertThatCode(() -> ArchitectureRules.TIME_FROM_CLOCK.check(new ClassFileImporter().importClasses(ReadsTheInjectedClock.class)))
                .doesNotThrowAnyException();
    }
}
