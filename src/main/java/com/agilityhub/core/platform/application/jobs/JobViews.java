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

    /**
     * AGENTS rule 4 (E5-T13, review E5-T10 #5): the club-scoped views of a run (D11 rows and history, the run sheet, the
     * club's manual trigger and its audit) leave out what the run did outside the tenant, the P9 platform pass: the
     * `platform…` counters (`WOULD_…_platform…` in a dry run) and the `Platform…` items. In a dry run each plan counter
     * (`WOULD_<action>`) is lowered by the platform items of that action: their total `WOULD_<action>_platformItems`,
     * recorded before the trace's 500-item cut (E5-T15, review E5-T13 #2); for a run stored before that counter existed,
     * the platform items the trace kept. The stored JobRun and the platform console keep everything.
     */
    public static JobRunView forClub(JobRunView view) {
        var all = view.effects().counters();
        var counters = clubCounters(all);
        var items = new java.util.ArrayList<JobEffectItem>();
        var traced = new java.util.HashMap<String, Long>();
        for (JobEffectItem item : view.effects().items()) {
            if (!platformItem(item.entityType())) { items.add(item); }
            else { traced.merge(item.action(), 1L, Long::sum); }
        }
        if (view.dryRun()) {
            counters.replaceAll((key, value) -> {
                if (!key.startsWith("WOULD_")) { return value; }
                Long platform = all.get(platformPlanCounter(key.substring("WOULD_".length())));
                return value - (platform != null ? platform : traced.getOrDefault(key, 0L));
            });
        }
        return new JobRunView(view.runId(), view.job(), view.scheduledFor(), view.scheduledForLocal(), view.timeZone(), view.trigger(), view.dryRun(),
                view.status(), view.skipReason(), view.startedAt(), view.finishedAt(), view.durationMs(), new JobEffects(counters, List.copyOf(items)),
                view.errors(), view.actorAccountId(), view.parametersSnapshot());
    }
    public static JobRunView forClub(com.agilityhub.core.platform.persistence.jobs.JobRun run) { return forClub(view(run)); }
    /** The counters without the platform pass ones (see {@link #forClub(JobRunView)}). */
    public static Map<String, Long> clubCounters(Map<String, Long> counters) {
        var result = new java.util.LinkedHashMap<String, Long>();
        counters.forEach((key, value) -> { if (!platformCounter(key)) { result.put(key, value); } });
        return result;
    }
    static boolean platformCounter(String key) { return key.startsWith("platform") || key.startsWith("WOULD_") && key.contains("_platform"); }
    /** An item of the P9 platform pass (outside every tenant). */
    static boolean platformItem(String entityType) { return entityType.startsWith("Platform"); }
    /** The dry-run counter of the platform items planned with `action` (hidden from the club like every `WOULD_…_platform…`). */
    static String platformPlanCounter(String action) { return "WOULD_" + action + "_platformItems"; }

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
