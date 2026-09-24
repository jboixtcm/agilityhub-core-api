package com.agilityhub.core.arch;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
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
    static final class ReadsTheSystemClockInAZone {
        private static final ZoneId ZONE = ZoneId.of("Europe/Madrid");
        LocalDate today() { return LocalDate.now(ZONE); }
        LocalDateTime dateTime() { return LocalDateTime.now(ZONE); }
        LocalTime time() { return LocalTime.now(ZONE); }
        ZonedDateTime zoned() { return ZonedDateTime.now(ZONE); }
        OffsetDateTime offset() { return OffsetDateTime.now(ZONE); }
        Calendar calendar() { return Calendar.getInstance(); }
    }
    static final class ReadsTheSystemClockThroughOtherTypes {
        private static final ZoneId ZONE = ZoneId.of("Europe/Madrid");
        OffsetTime offsetTime() { return OffsetTime.now(); }
        OffsetTime offsetTimeInZone() { return OffsetTime.now(ZONE); }
        Year year() { return Year.now(); }
        Year yearInZone() { return Year.now(ZONE); }
        YearMonth month() { return YearMonth.now(); }
        YearMonth monthInZone() { return YearMonth.now(ZONE); }
        MonthDay day() { return MonthDay.now(); }
        MonthDay dayInZone() { return MonthDay.now(ZONE); }
    }
    static final class ReadsTheInjectedClock {
        private final Clock clock;
        ReadsTheInjectedClock(Clock clock) { this.clock = clock; }
        LocalDate today() { return LocalDate.now(clock); }
        Instant now() { return clock.instant(); }
        Year year() { return Year.now(clock); }
        YearMonth month() { return YearMonth.now(clock); }
        MonthDay day() { return MonthDay.now(clock); }
        OffsetTime time() { return OffsetTime.now(clock); }
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

    @Test void T_15_07_nowWithAZoneAndCalendarGetInstanceAreViolations() {
        var result = ArchitectureRules.TIME_FROM_CLOCK.evaluate(new ClassFileImporter().importClasses(ReadsTheSystemClockInAZone.class));
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails()).anyMatch(line -> line.contains("LocalDate.now(java.time.ZoneId)"))
                .anyMatch(line -> line.contains("LocalDateTime.now(java.time.ZoneId)"))
                .anyMatch(line -> line.contains("LocalTime.now(java.time.ZoneId)"))
                .anyMatch(line -> line.contains("ZonedDateTime.now(java.time.ZoneId)"))
                .anyMatch(line -> line.contains("OffsetDateTime.now(java.time.ZoneId)"))
                .anyMatch(line -> line.contains("Calendar.getInstance()"));
    }

    @Test void T_15_07_offsetTimeYearYearMonthAndMonthDayNowAreViolationsWithOrWithoutAZone() {
        var result = ArchitectureRules.TIME_FROM_CLOCK.evaluate(new ClassFileImporter().importClasses(ReadsTheSystemClockThroughOtherTypes.class));
        assertThat(result.hasViolation()).isTrue();
        var details = result.getFailureReport().getDetails();
        for (String type : new String[] {"OffsetTime", "Year", "YearMonth", "MonthDay"}) {
            assertThat(details).as(type).anyMatch(line -> line.contains("<java.time." + type + ".now()>"))
                    .anyMatch(line -> line.contains("<java.time." + type + ".now(java.time.ZoneId)>"));
        }
    }

    @Test void T_15_07_injectedClockReadsPass() {
        assertThatCode(() -> ArchitectureRules.TIME_FROM_CLOCK.check(new ClassFileImporter().importClasses(ReadsTheInjectedClock.class)))
                .doesNotThrowAnyException();
    }
}
