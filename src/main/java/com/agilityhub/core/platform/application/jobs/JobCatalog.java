package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import java.util.Optional;

/** S15 R-15-01: the ten R1 processes live in code, not in the database. */
public final class JobCatalog {
    private static final List<JobDefinition> ROWS = List.of(
            new JobDefinition(JobName.WEEK_OPENING, "week-opening", Cadence.WEEKLY, "bookings.weekOpensAt", null, null,
                    CatchUpWindow.HOURS_24, "jobs.weekOpening.enabled"),
            new JobDefinition(JobName.RISK_REVIEW, "risk-review", Cadence.DAILY, "classes.riskReviewTime", null, null,
                    CatchUpWindow.END_OF_LOCAL_DAY, "jobs.riskReview.enabled"),
            new JobDefinition(JobName.NO_SHOW_NOTICES, "no-show-notices", Cadence.DAILY, "messaging.noShowNoticeTime", null, null,
                    CatchUpWindow.UNLIMITED, "jobs.noShowNotices.enabled"),
            new JobDefinition(JobName.REMINDERS, "reminders", Cadence.CONTINUOUS, null, null, null,
                    CatchUpWindow.CONTINUOUS, "jobs.reminders.enabled"),
            new JobDefinition(JobName.EXPIRATIONS, "expirations", Cadence.DAILY, "jobs.dailyTime", null, null,
                    CatchUpWindow.UNLIMITED, "jobs.expirations.enabled"),
            new JobDefinition(JobName.WAITLIST_FIFO, "waitlist-fifo", Cadence.CONTINUOUS, null, null, Module.WAITLIST,
                    CatchUpWindow.CONTINUOUS, "jobs.waitlistFifo.enabled"),
            new JobDefinition(JobName.PAYMENT_TIMEOUTS, "payment-timeouts", Cadence.CONTINUOUS, null, null, Module.SINGLE_CLASS,
                    CatchUpWindow.CONTINUOUS, "jobs.paymentTimeouts.enabled"),
            new JobDefinition(JobName.CLASS_FINISHING, "class-finishing", Cadence.CONTINUOUS, null, null, null,
                    CatchUpWindow.CONTINUOUS, "jobs.classFinishing.enabled"),
            new JobDefinition(JobName.CLEANUP, "cleanup", Cadence.DAILY, "jobs.dailyTime", null, null,
                    CatchUpWindow.UNLIMITED, "jobs.cleanup.enabled"),
            new JobDefinition(JobName.BILLING_REMINDER, "billing-reminder", Cadence.MONTHLY, "jobs.dailyTime", "billing.remittanceReminderDay",
                    Module.BILLING, CatchUpWindow.END_OF_LOCAL_MONTH, "jobs.billingReminder.enabled"));

    private JobCatalog() { }
    public static List<JobDefinition> all() { return ROWS; }
    public static Optional<JobDefinition> find(JobName name) { return ROWS.stream().filter(row -> row.name() == name).findFirst(); }
    public static JobDefinition definition(JobName name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("No catalog row for " + name));
    }
    /** `{name}` of the `/jobs/{name}` routes; an unknown route id is `JOB_UNKNOWN`. */
    public static JobDefinition byRoute(String routeId) {
        return ROWS.stream().filter(row -> row.routeId().equals(routeId)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_UNKNOWN));
    }
}
