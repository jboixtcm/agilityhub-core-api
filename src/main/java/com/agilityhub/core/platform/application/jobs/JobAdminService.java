package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.ParameterSettingsService;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.platform.persistence.jobs.JobRunRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import com.agilityhub.core.shared.application.lists.ListDataset;
import com.agilityhub.core.shared.application.lists.ListDefinition;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import static com.agilityhub.core.platform.application.jobs.JobViews.*;

/**
 * S15 §6 (R-15-09) behind D11 «Processos automàtics» and the S17 console: the process rows of the club, the run
 * history as a universal list, the run sheet, the manual trigger (audited in {@link JobTriggerService}), the switch
 * (the `jobs.<name>.enabled` parameter through S02) and the club × process health matrix.
 */
@Service
public class JobAdminService {
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final Set<SkipReason> NEUTRAL_SKIPS = EnumSet.of(SkipReason.DISABLED, SkipReason.MODULE_OFF, SkipReason.CLUB_INACTIVE, SkipReason.NOT_DUE);
    private final JobRunner runner; private final JobRunRepository runs; private final JobContractAccess access; private final JobTriggerService triggers;
    private final ParameterSettingsService parameters; private final ClubConfigService configs; private final ClubRepository clubs;
    private final ListEngine lists; private final Clock clock;
    public JobAdminService(JobRunner runner, JobRunRepository runs, JobContractAccess access, JobTriggerService triggers, ParameterSettingsService parameters,
            ClubConfigService configs, ClubRepository clubs, ListEngine lists, Clock clock) {
        this.runner = runner; this.runs = runs; this.access = access; this.triggers = triggers; this.parameters = parameters; this.configs = configs;
        this.clubs = clubs; this.lists = lists; this.clock = clock;
    }

    /** `GET /jobs`: catalog order, only processes with a registered implementation whose module (and extra guard) is on. */
    public JobSummaries summaries() {
        var config = configs.get(TenantContext.require());
        var items = new ArrayList<JobSummary>();
        for (JobDefinition definition : JobCatalog.all()) {
            var job = runner.registered(definition.name()).orElse(null);
            if (job == null || !JobRunner.moduleOn(job, config)) { continue; }
            items.add(summary(definition, config));
        }
        return new JobSummaries(items);
    }
    private JobSummary summary(JobDefinition definition, ClubConfig config) {
        var zone = ZoneId.of(config.club().timeZone());
        var schedule = JobSchedule.resolve(definition, key -> config.get(key, Object.class));
        String next = schedule.kind() == Cadence.CONTINUOUS ? null
                : JobOccurrences.next(clock.instant(), schedule, zone).map(at -> at.atZone(zone).toLocalDateTime().format(LOCAL)).orElse(null);
        return new JobSummary(definition.routeId(), definition.name().name(), definition.module(), enabled(definition, config),
                new JobScheduleView(schedule.kind(), schedule.localTime() == null ? null : schedule.localTime().toString(), schedule.dayOfWeek(), schedule.dayOfMonth()),
                next, runs.last(definition.name()).map(run -> lastRun(run, true)).orElse(null));
    }
    private static boolean enabled(JobDefinition definition, ClubConfig config) {
        return definition.switchParameter() == null || Boolean.TRUE.equals(config.get(definition.switchParameter(), Boolean.class));
    }
    /** `club` = a club-scoped view (D11), without the platform pass counters; the S17 console shows them. */
    static JobLastRun lastRun(JobRun run, boolean club) {
        var view = club ? JobViews.forClub(run) : JobViews.view(run);
        return new JobLastRun(run.id(), run.status(), run.finishedAt(), run.trigger(), run.dryRun(), view.effects().counters());
    }

    /** `GET /jobs/{name}/runs`: universal list over the club's `job_runs` of the process (CONVENCIONS_API §4). */
    public ListPage<JobRunListItem> runs(String routeId, MultiValueMap<String, String> params) {
        var definition = access.job(routeId);
        var filters = new HashMap<String, ListDefinition.Field>();
        filters.put("status", new ListDefinition.Field("status", ListDefinition.Type.TEXT));
        filters.put("trigger", new ListDefinition.Field("trigger", ListDefinition.Type.TEXT));
        filters.put("scheduledFor", new ListDefinition.Field("scheduledFor", ListDefinition.Type.INSTANT));
        filters.put("dryRun", new ListDefinition.Field("dryRun", ListDefinition.Type.BOOLEAN));
        var columns = List.of("scheduledForLocal", "trigger", "status", "dryRun", "durationMs", "counters", "errorCount", "skipReason");
        var list = new ListDefinition("job-runs", filters, Map.of("scheduledFor", "scheduledFor", "startedAt", "startedAt"), List.of(), columns, columns,
                List.of("startedAt,desc"), Set.of("id"));
        var dataset = new ListDataset(list, "job_runs", List.of(new Document("$match", new Document("job", definition.name().name()))),
                Map.of("id", "$_id"), Set.of(), (field, value) -> Objects.toString(value, ""));
        var page = lists.list(dataset, params);
        var ids = page.items().stream().map(row -> row.get("id").toString()).toList();
        var byId = new HashMap<String, JobRun>(); runs.byIds(ids).forEach(run -> byId.put(run.id(), run));
        var items = ids.stream().map(byId::get).filter(Objects::nonNull).map(run -> {
            var view = JobViews.forClub(run);
            return new JobRunListItem(run.id(), run.scheduledFor(), run.scheduledForLocal(), run.trigger(), run.dryRun(), run.status(), run.skipReason(),
                    run.startedAt(), run.finishedAt(), run.durationMs(), view.effects().counters(), run.errors().size());
        }).toList();
        return new ListPage<>(items, page.page(), page.size(), page.totalItems(), page.totalPages(), page.appliedFilters());
    }

    /** `GET /jobs/{name}/runs/{runId}` (R-15-21 run sheet). */
    public JobRunView run(String routeId, String runId) {
        var definition = access.job(routeId);
        return runs.forJob(definition.name(), runId).map(JobViews::forClub).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** `POST /jobs/{name}/trigger` (R-15-09): same lock and code as the calendar, also with the switch off. Club-scoped view. */
    public JobRunView trigger(String routeId, boolean dryRun) { return JobViews.forClub(triggered(routeId, dryRun)); }
    private JobRunView triggered(String routeId, boolean dryRun) {
        var definition = access.job(routeId);
        return triggers.trigger(definition.name(), dryRun);
    }

    /** `PUT /jobs/{name}/switch`: S02 parameter write (`ParameterChanged`, audited PARAMETER_CHANGED, config cache invalidated). */
    public JobSwitchResponse switchJob(String routeId, boolean enabled) {
        var definition = access.job(routeId);
        var current = parameters.view(definition.switchParameter(), null);
        var saved = parameters.update(definition.switchParameter(), enabled, null, current.version(), null);
        return new JobSwitchResponse(definition.routeId(), Boolean.TRUE.equals(saved.value()));
    }

    /** `POST /platform/clubs/{clubId}/jobs/{name}/trigger`: the club route inside the addressed club's scope. */
    public JobRunView platformTrigger(String clubId, String routeId, boolean dryRun) {
        access.platformJob(clubId, routeId);
        try (var scope = TenantContext.open(clubId)) { return triggered(routeId, dryRun); }
    }

    /**
     * `GET /platform/jobs/overview` (S17): OK = last success inside the period · WARN = last execution PARTIAL ·
     * ALERT = last execution FAILED / MISSED_WINDOW, or no success in more than two periods. A process switched off,
     * or one that has never executed (its only trace is the E33 first-run baseline), is OK: nothing is overdue.
     */
    public PlatformJobsOverview overview(String clubId, JobHealth status) {
        var result = new ArrayList<PlatformClubJobs>();
        for (Club club : clubs.schedulableClubs()) {
            if (clubId != null && !clubId.equals(club.id())) { continue; }
            try (var scope = TenantContext.open(club.id())) {
                var config = configs.get(club.id());
                var cells = new ArrayList<PlatformJobCell>();
                for (JobDefinition definition : JobCatalog.all()) {
                    var job = runner.registered(definition.name()).orElse(null);
                    if (job == null || !JobRunner.moduleOn(job, config)) { continue; }
                    boolean on = enabled(definition, config);
                    var last = runs.last(definition.name()).orElse(null);
                    var latest = runs.lastExecution(definition.name(), NEUTRAL_SKIPS).orElse(null);
                    var health = health(definition, on, baseline(latest) ? null : latest, runs.lastSuccess(definition.name()).orElse(null));
                    if (status == null || status == health) { cells.add(new PlatformJobCell(definition.routeId(), on, last == null ? null : lastRun(last, false), health)); }
                }
                if (!cells.isEmpty()) { result.add(new PlatformClubJobs(club.id(), config.club().name(), config.club().timeZone(), cells)); }
            }
        }
        if (clubId != null && result.isEmpty() && clubs.findById(clubId).isEmpty()) { throw new ApiException(ErrorCode.NOT_FOUND); }
        return new PlatformJobsOverview(result);
    }
    /** R-15-05 (E33): a MISSED_WINDOW that is the first JobRun of the process is its baseline, so it reads as «never executed». */
    private boolean baseline(JobRun latest) {
        return latest != null && latest.skipReason() == SkipReason.MISSED_WINDOW
                && runs.first(latest.job()).map(JobRun::id).filter(latest.id()::equals).isPresent();
    }
    JobHealth health(JobDefinition definition, boolean enabled, JobRun latest, JobRun success) {
        if (!enabled || latest == null) { return JobHealth.OK; }
        if (latest.status() == JobStatus.FAILED || latest.skipReason() == SkipReason.MISSED_WINDOW) { return JobHealth.ALERT; }
        Duration period = switch (definition.cadence()) {
            case CONTINUOUS -> Duration.ofMinutes(1);
            case DAILY -> Duration.ofDays(1);
            case WEEKLY -> Duration.ofDays(7);
            case MONTHLY -> Duration.ofDays(31);
        };
        // Two periods plus the on-time tolerance of R-15-05 before a silent process turns red.
        Instant limit = clock.instant().minus(period.multipliedBy(2)).minus(JobOccurrences.ON_TIME);
        if (success == null || success.startedAt().isBefore(limit)) { return JobHealth.ALERT; }
        return latest.status() == JobStatus.PARTIAL ? JobHealth.WARN : JobHealth.OK;
    }
}
