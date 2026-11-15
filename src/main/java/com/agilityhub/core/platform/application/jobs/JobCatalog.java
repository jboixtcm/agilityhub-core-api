package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import java.util.Optional;

/**
 * S15 R-15-01: the R1 processes live in code, not in the database. E11-T01 adds S14's two (R-14-15, R-14-16): no switch (a legal
 * obligation of the club, not an option), and the sweep runs on the first day of the month (a product constant, no parameter).
 */
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
                    Module.BILLING, CatchUpWindow.END_OF_LOCAL_MONTH, "jobs.billingReminder.enabled"),
            new JobDefinition(JobName.ERASURES, "erasures", Cadence.DAILY, "jobs.dailyTime", null, null, CatchUpWindow.UNLIMITED, null),
            new JobDefinition(JobName.RETENTION_SWEEP, "retention-sweep", Cadence.MONTHLY, "jobs.dailyTime", null, null,
                    CatchUpWindow.END_OF_LOCAL_MONTH, null));

    /**
     * JOB-01 (owner decision of 13 Nov 2026, R-15-10): the processes that affect members — the risk review (P2), the reminders
     * (P4), the expirations (P5) and the billing-related runs, payment timeouts (P7) and the remittance reminder (P10) — whose
     * PARTIAL run also publishes `JobFailed` (N-42 to the club's admins), as a FAILED run of any process does.
     */
    private static final java.util.Set<JobName> ALERT_ON_PARTIAL = java.util.EnumSet.of(JobName.RISK_REVIEW, JobName.REMINDERS, JobName.EXPIRATIONS,
            JobName.PAYMENT_TIMEOUTS, JobName.BILLING_REMINDER);

    private JobCatalog() { }
    public static List<JobDefinition> all() { return ROWS; }
    /** JOB-01: whether a PARTIAL run of the process alerts the club's admins like a FAILED one. */
    public static boolean alertsOnPartial(JobName name) { return ALERT_ON_PARTIAL.contains(name); }
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
