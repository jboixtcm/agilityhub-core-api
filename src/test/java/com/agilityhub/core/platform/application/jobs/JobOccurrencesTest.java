package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static com.agilityhub.core.platform.application.jobs.JobOccurrences.Outcome.*;
import static org.assertj.core.api.Assertions.*;

/** S15 R-15-01/02/05 without Spring or Mongo (T-15-01, T-15-02, T-15-04). */
class JobOccurrencesTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    static final JobSchedule REVIEW = new JobSchedule(Cadence.DAILY, LocalTime.of(7, 30), null, null);
    static final JobSchedule OPENING = new JobSchedule(Cadence.WEEKLY, LocalTime.of(20, 0), DayOfWeek.SUNDAY, null);
    static Instant at(String instant) { return Instant.parse(instant); }

    /** A daily job is due when its latest occurrence differs from the one already executed (R-15-02). */
    static boolean due(Instant now, JobSchedule schedule, ZoneId zone, Instant alreadyRun) {
        return !JobOccurrences.lastDue(now, schedule, zone).orElseThrow().equals(alreadyRun);
    }

    @Test void T_15_01_eachClubIsDueAtItsOwnLocalHour() {
        Instant madridYesterday = at("2026-10-04T05:30:00Z"), buenosAiresYesterday = at("2026-10-04T10:30:00Z");
        assertThat(due(at("2026-10-05T05:29:00Z"), REVIEW, MADRID, madridYesterday)).isFalse();
        assertThat(due(at("2026-10-05T05:29:00Z"), REVIEW, BUENOS_AIRES, buenosAiresYesterday)).isFalse();
        assertThat(due(at("2026-10-05T05:30:00Z"), REVIEW, MADRID, madridYesterday)).isTrue();
        assertThat(due(at("2026-10-05T05:30:00Z"), REVIEW, BUENOS_AIRES, buenosAiresYesterday)).isFalse();
        assertThat(JobOccurrences.lastDue(at("2026-10-05T05:30:00Z"), REVIEW, MADRID)).contains(at("2026-10-05T05:30:00Z"));
        assertThat(due(at("2026-10-05T10:30:00Z"), REVIEW, BUENOS_AIRES, buenosAiresYesterday)).isTrue();
        assertThat(JobOccurrences.lastDue(at("2026-10-05T10:30:00Z"), REVIEW, BUENOS_AIRES)).contains(at("2026-10-05T10:30:00Z"));
        // Week opening of Sunday 04-10-2026 20:00 local.
        assertThat(JobOccurrences.lastDue(at("2026-10-04T18:00:00Z"), OPENING, MADRID)).contains(at("2026-10-04T18:00:00Z"));
        assertThat(JobOccurrences.lastDue(at("2026-10-04T17:59:00Z"), OPENING, MADRID)).contains(at("2026-09-27T18:00:00Z"));
        assertThat(JobOccurrences.lastDue(at("2026-10-04T23:00:00Z"), OPENING, BUENOS_AIRES)).contains(at("2026-10-04T23:00:00Z"));
        assertThat(JobOccurrences.lastDue(at("2026-10-04T22:59:00Z"), OPENING, BUENOS_AIRES)).contains(at("2026-09-27T23:00:00Z"));
    }

    @Test void T_15_02_daylightSavingTakesTheFirstOccurrenceAndShiftsGapsForward() {
        var halfPastTwo = new JobSchedule(Cadence.DAILY, LocalTime.of(2, 30), null, null);
        // 25-10-2026: 02:00–03:00 happens twice; the first (CEST) occurrence wins and is not due again an hour later.
        assertThat(JobOccurrences.occurrence(java.time.LocalDate.of(2026, 10, 25), LocalTime.of(2, 30), MADRID)).isEqualTo(at("2026-10-25T00:30:00Z"));
        assertThat(JobOccurrences.lastDue(at("2026-10-25T00:30:00Z"), halfPastTwo, MADRID)).contains(at("2026-10-25T00:30:00Z"));
        assertThat(JobOccurrences.lastDue(at("2026-10-25T01:30:00Z"), halfPastTwo, MADRID)).contains(at("2026-10-25T00:30:00Z"));
        // 29-03-2026: 02:30 does not exist; it runs once at 03:30 CEST.
        assertThat(JobOccurrences.lastDue(at("2026-03-29T01:30:00Z"), halfPastTwo, MADRID)).contains(at("2026-03-29T01:30:00Z"));
        assertThat(JobOccurrences.lastDue(at("2026-03-29T01:29:00Z"), halfPastTwo, MADRID)).contains(at("2026-03-28T01:30:00Z"));
        // Opening of 25-10 is 20:00 CET; the booking week lasts 169 h.
        assertThat(JobOccurrences.lastDue(at("2026-10-25T19:00:00Z"), OPENING, MADRID)).contains(at("2026-10-25T19:00:00Z"));
        assertThat(Duration.between(at("2026-10-18T18:00:00Z"), JobOccurrences.lastDue(at("2026-10-25T19:00:00Z"), OPENING, MADRID).orElseThrow()).toHours()).isEqualTo(169);
        var daily = new JobSchedule(Cadence.DAILY, LocalTime.of(6, 0), null, null);
        assertThat(JobOccurrences.lastDue(at("2026-10-24T12:00:00Z"), daily, MADRID)).contains(at("2026-10-24T04:00:00Z"));
        assertThat(JobOccurrences.lastDue(at("2026-10-25T12:00:00Z"), daily, MADRID)).contains(at("2026-10-25T05:00:00Z"));
        // Club B has no daylight saving: same UTC hour both days.
        assertThat(JobOccurrences.lastDue(at("2026-10-25T12:00:00Z"), halfPastTwo, BUENOS_AIRES)).contains(at("2026-10-25T05:30:00Z"));
        assertThat(JobOccurrences.lastDue(at("2026-03-29T12:00:00Z"), halfPastTwo, BUENOS_AIRES)).contains(at("2026-03-29T05:30:00Z"));
    }

    @Test void T_15_04_lateRunsAreOnScheduleCaughtUpOrMissedByCatalogWindow() {
        var opening = JobCatalog.definition(JobName.WEEK_OPENING);
        Instant opensAt = at("2026-10-04T18:00:00Z");
        assertThat(JobOccurrences.triggerFor(opensAt.plus(Duration.ofMinutes(1)), opensAt, opening.catchUpWindow(), MADRID)).isEqualTo(SCHEDULE);
        assertThat(JobOccurrences.triggerFor(opensAt.plus(Duration.ofMinutes(2)), opensAt, opening.catchUpWindow(), MADRID)).isEqualTo(SCHEDULE);
        assertThat(JobOccurrences.triggerFor(opensAt.plus(Duration.ofHours(3)), opensAt, opening.catchUpWindow(), MADRID)).isEqualTo(CATCH_UP);
        assertThat(JobOccurrences.triggerFor(opensAt.plus(Duration.ofHours(24)), opensAt, opening.catchUpWindow(), MADRID)).isEqualTo(CATCH_UP);
        assertThat(JobOccurrences.triggerFor(opensAt.plus(Duration.ofHours(26)), opensAt, opening.catchUpWindow(), MADRID)).isEqualTo(MISSED_WINDOW);
        var review = JobCatalog.definition(JobName.RISK_REVIEW);
        Instant reviewAt = at("2026-10-05T05:30:00Z");
        assertThat(JobOccurrences.triggerFor(at("2026-10-05T21:59:00Z"), reviewAt, review.catchUpWindow(), MADRID)).isEqualTo(CATCH_UP);
        Instant nextDay = at("2026-10-05T22:01:00Z");
        assertThat(JobOccurrences.lastDue(nextDay, REVIEW, MADRID)).contains(reviewAt);
        assertThat(JobOccurrences.triggerFor(nextDay, reviewAt, review.catchUpWindow(), MADRID)).isEqualTo(MISSED_WINDOW);
        var expirations = JobCatalog.definition(JobName.EXPIRATIONS);
        assertThat(JobOccurrences.triggerFor(at("2026-10-08T04:00:00Z"), at("2026-10-05T04:00:00Z"), expirations.catchUpWindow(), MADRID)).isEqualTo(CATCH_UP);
        var billing = JobCatalog.definition(JobName.BILLING_REMINDER);
        assertThat(JobOccurrences.triggerFor(at("2026-10-31T21:00:00Z"), at("2026-10-22T04:00:00Z"), billing.catchUpWindow(), MADRID)).isEqualTo(CATCH_UP);
        assertThat(JobOccurrences.triggerFor(at("2026-10-31T23:30:00Z"), at("2026-10-22T04:00:00Z"), billing.catchUpWindow(), MADRID)).isEqualTo(MISSED_WINDOW);
        var fifo = JobCatalog.definition(JobName.WAITLIST_FIFO);
        assertThat(JobOccurrences.triggerFor(at("2026-10-31T23:30:00Z"), at("2026-10-01T00:00:00Z"), fifo.catchUpWindow(), MADRID)).isEqualTo(SCHEDULE);
    }

    @Test void T_15_02_monthlyContinuousAndNextOccurrences() {
        var monthly = new JobSchedule(Cadence.MONTHLY, LocalTime.of(6, 0), null, 31);
        assertThat(JobOccurrences.lastDue(at("2026-02-28T05:00:00Z"), monthly, MADRID)).contains(at("2026-02-28T05:00:00Z"));
        assertThat(JobOccurrences.lastDue(at("2026-02-28T04:59:00Z"), monthly, MADRID)).contains(at("2026-01-31T05:00:00Z"));
        assertThat(JobOccurrences.lastDue(at("2026-02-10T12:00:00Z"), monthly, MADRID)).contains(at("2026-01-31T05:00:00Z"));
        assertThat(JobOccurrences.next(at("2026-02-10T12:00:00Z"), monthly, MADRID)).contains(at("2026-02-28T05:00:00Z"));
        assertThat(JobOccurrences.next(at("2026-02-28T05:00:00Z"), monthly, MADRID)).contains(at("2026-03-31T04:00:00Z"));
        var never = new JobSchedule(Cadence.MONTHLY, LocalTime.of(6, 0), null, 0);
        assertThat(JobOccurrences.lastDue(at("2026-02-10T12:00:00Z"), never, MADRID)).isEmpty();
        assertThat(JobOccurrences.next(at("2026-02-10T12:00:00Z"), never, MADRID)).isEmpty();
        assertThat(JobOccurrences.lastDue(at("2026-02-10T12:00:00Z"), new JobSchedule(Cadence.MONTHLY, LocalTime.of(6, 0), null, null), MADRID)).isEmpty();
        var continuous = new JobSchedule(Cadence.CONTINUOUS, null, null, null);
        assertThat(JobOccurrences.lastDue(at("2026-02-10T12:00:42Z"), continuous, MADRID)).contains(at("2026-02-10T12:00:00Z"));
        assertThat(JobOccurrences.next(at("2026-02-10T12:00:42Z"), continuous, MADRID)).contains(at("2026-02-10T12:01:00Z"));
        assertThat(JobOccurrences.next(at("2026-10-05T05:29:00Z"), REVIEW, MADRID)).contains(at("2026-10-05T05:30:00Z"));
        assertThat(JobOccurrences.next(at("2026-10-05T05:30:00Z"), REVIEW, MADRID)).contains(at("2026-10-06T05:30:00Z"));
        assertThat(JobOccurrences.next(at("2026-10-04T17:00:00Z"), OPENING, MADRID)).contains(at("2026-10-04T18:00:00Z"));
        assertThat(JobOccurrences.next(at("2026-10-04T18:00:00Z"), OPENING, MADRID)).contains(at("2026-10-11T18:00:00Z"));
    }

    @Test void T_15_01_catalogHasTheTenR1ProcessesWithTheirRowsAndResolvesClubSchedules() {
        assertThat(JobCatalog.all()).extracting(JobDefinition::routeId).containsExactly("week-opening", "risk-review", "no-show-notices", "reminders",
                "expirations", "waitlist-fifo", "payment-timeouts", "class-finishing", "cleanup", "billing-reminder");
        assertThat(JobCatalog.all()).extracting(JobDefinition::name).containsExactly(java.util.Arrays.copyOf(JobName.values(), 10));
        assertThat(JobCatalog.find(JobName.TEST_NOOP)).isEmpty();
        assertThatThrownBy(() -> JobCatalog.definition(JobName.TEST_NOOP)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobCatalog.byRoute("foo")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.JOB_UNKNOWN));
        assertThat(JobCatalog.byRoute("payment-timeouts").module()).isEqualTo(Module.SINGLE_CLASS);
        assertThat(JobCatalog.definition(JobName.WAITLIST_FIFO).module()).isEqualTo(Module.WAITLIST);
        assertThat(JobCatalog.definition(JobName.BILLING_REMINDER).module()).isEqualTo(Module.BILLING);
        assertThat(JobCatalog.all()).allSatisfy(row -> assertThat(row.switchParameter()).startsWith("jobs.").endsWith(".enabled"));
        Map<String, Object> parameters = Map.of("bookings.weekOpensAt", Map.of("dayOfWeek", "SUNDAY", "time", "20:00"),
                "classes.riskReviewTime", "07:30", "jobs.dailyTime", "06:00", "billing.remittanceReminderDay", 22);
        assertThat(JobSchedule.resolve(JobCatalog.definition(JobName.WEEK_OPENING), parameters::get)).isEqualTo(OPENING);
        assertThat(JobSchedule.resolve(JobCatalog.definition(JobName.RISK_REVIEW), parameters::get)).isEqualTo(REVIEW);
        assertThat(JobSchedule.resolve(JobCatalog.definition(JobName.BILLING_REMINDER), parameters::get))
                .isEqualTo(new JobSchedule(Cadence.MONTHLY, LocalTime.of(6, 0), null, 22));
        assertThat(JobSchedule.resolve(JobCatalog.definition(JobName.REMINDERS), parameters::get).kind()).isEqualTo(Cadence.CONTINUOUS);
        assertThat(CatchUpWindow.CONTINUOUS.covers(at("2020-01-01T00:00:00Z"), at("2026-01-01T00:00:00Z"), MADRID)).isTrue();
        assertThat(CatchUpWindow.UNLIMITED.covers(at("2020-01-01T00:00:00Z"), at("2026-01-01T00:00:00Z"), MADRID)).isTrue();
    }

    @Test void T_15_10_itemsAndEffectsNormaliseMissingValues() {
        assertThat(new JobItem("ClassSession", "c1", "CANCEL").detail()).isEmpty();
        assertThat(new JobItem("ClassSession", "c1", "CANCEL", null).detail()).isEmpty();
        assertThat(new JobEffect("CANCEL", null, null).counters()).isEmpty();
        assertThat(JobEffect.of("CANCEL", "cancelled").counters()).containsEntry("cancelled", 1L);
    }
}
