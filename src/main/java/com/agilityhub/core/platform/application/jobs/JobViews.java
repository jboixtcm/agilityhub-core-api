package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.application.Module;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * S15 §6 wire forms, shared by the club routes (`clubs.common.api`) and the platform console (`platform.api`).
 * `job` is the R-15-01 `JobName`; the test-only process is never published.
 */
public final class JobViews {
    private JobViews() { }

    @Schema(name = "JobRun", description = "S15 §3 execution trace; in a dry run effects.items is the plan (actions prefixed WOULD_).")
    public record JobRunView(String runId,
            @Schema(allowableValues = {"WEEK_OPENING", "RISK_REVIEW", "NO_SHOW_NOTICES", "REMINDERS", "EXPIRATIONS", "WAITLIST_FIFO",
                    "PAYMENT_TIMEOUTS", "CLASS_FINISHING", "CLEANUP", "BILLING_REMINDER"}) String job,
            Instant scheduledFor, @Schema(example = "2026-10-05T07:30") String scheduledForLocal, @Schema(example = "Europe/Madrid") String timeZone,
            JobTrigger trigger, boolean dryRun, JobStatus status,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) SkipReason skipReason,
            Instant startedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant finishedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Long durationMs,
            JobEffects effects, List<JobError> errors,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only for MANUAL runs") String actorAccountId,
            @Schema(description = "Values of the parameters the run read, for example classes.minDogs") Map<String, Object> parametersSnapshot) { }
    public record JobEffects(@Schema(description = "Counter name to value") Map<String, Long> counters,
            @Schema(description = "At most 500 items; the rest are only counted") List<JobEffectItem> items) { }
    public record JobEffectItem(String entityType, String entityId, String action,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Map<String, Object> detail) { }
    public record JobError(@Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Null for a job-level failure") String entityId,
            String code, String message, String traceId) { }

    public record JobRunListItem(String runId, Instant scheduledFor, String scheduledForLocal, JobTrigger trigger, boolean dryRun, JobStatus status,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) SkipReason skipReason, Instant startedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant finishedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Long durationMs,
            Map<String, Long> counters, int errorCount) { }

    public record JobScheduleView(Cadence kind,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, example = "07:30") String localTime,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) DayOfWeek dayOfWeek,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer dayOfMonth) { }
    public record JobLastRun(String runId, JobStatus status, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant finishedAt,
            JobTrigger trigger, boolean dryRun, Map<String, Long> counters) { }
    public record JobSummary(@Schema(description = "R-15-01 route id", example = "risk-review") String name,
            @Schema(allowableValues = {"WEEK_OPENING", "RISK_REVIEW", "NO_SHOW_NOTICES", "REMINDERS", "EXPIRATIONS", "WAITLIST_FIFO",
                    "PAYMENT_TIMEOUTS", "CLASS_FINISHING", "CLEANUP", "BILLING_REMINDER"}) String jobName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Module module, boolean enabled, JobScheduleView schedule,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, example = "2026-10-06T07:30") String nextScheduledForLocal,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) JobLastRun lastRun) { }
    public record JobSummaries(@Schema(description = "Only processes with a registered implementation and an enabled module") List<JobSummary> items) { }
    public record JobTriggerRequest(@jakarta.validation.constraints.NotNull Boolean dryRun) { }
    public record JobSwitchRequest(@jakarta.validation.constraints.NotNull Boolean enabled) { }
    public record JobSwitchResponse(String name, boolean enabled) { }

    public enum JobHealth { OK, WARN, ALERT }
    public record PlatformJobCell(String name, boolean enabled, @Schema(requiredMode = NOT_REQUIRED, nullable = true) JobLastRun lastRun, JobHealth health) { }
    public record PlatformClubJobs(String clubId, String name, String timeZone, List<PlatformJobCell> jobs) { }
    public record PlatformJobsOverview(List<PlatformClubJobs> clubs) { }

    public static JobRunView view(com.agilityhub.core.platform.persistence.jobs.JobRun run) {
        var counters = new java.util.LinkedHashMap<String, Long>();
        run.counters().forEach(entry -> counters.put(entry.key(), ((Number) entry.value()).longValue()));
        var snapshot = new java.util.LinkedHashMap<String, Object>();
        run.parametersSnapshot().forEach(entry -> snapshot.put(entry.key(), entry.value()));
        var items = run.items().stream().map(item -> {
            var detail = new java.util.LinkedHashMap<String, Object>();
            item.detail().forEach(entry -> detail.put(entry.key(), entry.value()));
            return new JobEffectItem(item.entityType(), item.entityId(), item.action(), detail.isEmpty() ? null : detail);
        }).toList();
        var errors = run.errors().stream().map(e -> new JobError(e.entityId(), e.code(), e.message(), e.traceId())).toList();
        return new JobRunView(run.id(), run.job().name(), run.scheduledFor(), run.scheduledForLocal(), run.timeZone(), run.trigger(), run.dryRun(),
                run.status(), run.skipReason(), run.startedAt(), run.finishedAt(), run.durationMs(), new JobEffects(counters, items), errors,
                run.actorAccountId(), snapshot);
    }
}
