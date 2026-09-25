package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.persistence.jobs.JobLockRepository;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.platform.persistence.jobs.JobRunRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S15 R-15-03…R-15-10: guards, lease, one Mongo transaction per item, dry run and trace of every execution.
 * Scheduled and manual executions share the same lock and the same code path.
 */
@Service
public class JobRunner {
    static final Duration LEASE = Duration.ofSeconds(300);
    static final Duration RENEWAL = Duration.ofSeconds(60);
    static final Duration CONTINUOUS_SKIP_INTERVAL = Duration.ofHours(1);
    static final int MAX_ITEMS = 500;
    private static final Logger LOG = LoggerFactory.getLogger(JobRunner.class);
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final JobRunRepository runs;
    private final JobLockRepository locks;
    private final ClubConfigService configs;
    private final TransactionTemplate transactions;
    private final EventPublisher events;
    private final Clock clock;
    private final JobMetrics metrics;
    private final ObjectProvider<Job> jobs;
    private final String instance = UUID.randomUUID().toString();

    public JobRunner(JobRunRepository runs, JobLockRepository locks, ClubConfigService configs, TransactionTemplate transactions,
            EventPublisher events, Clock clock, JobMetrics metrics, ObjectProvider<Job> jobs) {
        this.runs = runs; this.locks = locks; this.configs = configs; this.transactions = transactions;
        this.events = events; this.clock = clock; this.metrics = metrics; this.jobs = jobs;
    }

    private record Request(String clubId, Job job, JobDefinition definition, ClubConfig config, ZoneId zone, Instant scheduledFor,
            JobTrigger trigger, boolean dryRun, String actorAccountId) { }

    /** Registered implementations in R-15-01 order; jobs outside the catalog (the test job) come last. */
    public List<Job> registered() {
        var list = new ArrayList<Job>(jobs.orderedStream().toList());
        list.sort(java.util.Comparator.comparing(Job::name));
        return list;
    }
    public Optional<Job> registered(JobName name) { return registered().stream().filter(job -> job.name() == name).findFirst(); }

    /** Module of the row plus the job's own extra condition (R-15-03). */
    public static boolean moduleOn(Job job, ClubConfig config) {
        var module = job.definition().module();
        return (module == null || config.modules().contains(module)) && job.activeFor(config);
    }

    /** One tick decision for one club and one job (R-15-01/02/03/05/06). Empty = NOT_DUE or claimed elsewhere. */
    public Optional<JobRun> scheduled(String clubId, boolean clubActive, Job job, Instant now) {
        try (var scope = TenantContext.open(clubId)) {
            var definition = job.definition();
            var config = configs.get(clubId);
            var zone = ZoneId.of(config.club().timeZone());
            reap(clubId, definition.name());
            var due = JobOccurrences.lastDue(now, JobSchedule.resolve(definition, key -> config.get(key, Object.class)), zone);
            if (due.isEmpty()) { return Optional.empty(); }
            Instant scheduledFor = due.get();
            boolean continuous = definition.cadence() == Cadence.CONTINUOUS;
            if (!continuous && runs.occurrenceTaken(definition.name(), scheduledFor)) { return Optional.empty(); }
            var outcome = JobOccurrences.triggerFor(now, scheduledFor, definition.catchUpWindow(), zone);
            var request = new Request(clubId, job, definition, config, zone, scheduledFor,
                    outcome == JobOccurrences.Outcome.SCHEDULE ? JobTrigger.SCHEDULE : JobTrigger.CATCH_UP, false, null);
            SkipReason skip = !clubActive ? SkipReason.CLUB_INACTIVE
                    : !moduleOn(job, config) ? SkipReason.MODULE_OFF
                    : !enabled(definition, config) ? SkipReason.DISABLED
                    : outcome == JobOccurrences.Outcome.MISSED_WINDOW ? SkipReason.MISSED_WINDOW : null;
            if (skip != null) { return skip(request, skip, now); }
            return execute(request, now);
        }
    }

    /** R-15-09: same lock and code; runs even when the switch is off; a module that is off is 404. */
    public JobRun manual(String clubId, JobName name, boolean dryRun, String actorAccountId) {
        try (var scope = TenantContext.open(clubId)) {
            var job = registered(name).orElseThrow(() -> new ApiException(ErrorCode.JOB_UNKNOWN));
            var config = configs.get(clubId);
            if (!moduleOn(job, config)) { throw new ApiException(ErrorCode.MODULE_DISABLED); }
            reap(clubId, name);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MINUTES);
            var request = new Request(clubId, job, job.definition(), config, ZoneId.of(config.club().timeZone()), now,
                    JobTrigger.MANUAL, dryRun, actorAccountId);
            return execute(request, now).orElseThrow(() -> new ApiException(ErrorCode.JOB_ALREADY_RUNNING));
        }
    }

    private static boolean enabled(JobDefinition definition, ClubConfig config) {
        return definition.switchParameter() == null || Boolean.TRUE.equals(config.get(definition.switchParameter(), Boolean.class));
    }

    private Optional<JobRun> skip(Request request, SkipReason reason, Instant now) {
        var name = request.definition().name();
        if (request.definition().cadence() == Cadence.CONTINUOUS && runs.skippedSince(name, reason, now.minus(CONTINUOUS_SKIP_INTERVAL))) {
            return Optional.empty();
        }
        // R-15-05 (E33): the first occurrence of a process in a club with no JobRun of it is a baseline, not a lost run.
        boolean baseline = reason == SkipReason.MISSED_WINDOW && !runs.any(name);
        var run = new JobRun(UUID.randomUUID().toString(), request.clubId(), name, request.scheduledFor(), local(request), request.zone().getId(),
                request.trigger(), request.dryRun(), JobStatus.SKIPPED, reason, now, now, 0L, List.of(), List.of(), List.of(),
                request.actorAccountId(), entries(baseSnapshot(request)), false, null, false);
        if (reason == SkipReason.MISSED_WINDOW && !baseline) {
            // R-15-10: a missed occurrence alerts like a failure (JobFailed → N-42).
            write(() -> { runs.insert(run); events.publish(failed(run)); return run; });
        } else {
            runs.insert(run);
        }
        LOG.info("Job skipped jobRunId={} job={} clubId={} reason={} baseline={}", run.id(), name, request.clubId(), reason, baseline);
        return Optional.of(run);
    }

    private Optional<JobRun> execute(Request request, Instant now) {
        var name = request.definition().name();
        String lock = request.clubId() + ":" + name;
        String holder = instance + ":" + UUID.randomUUID();
        if (request.dryRun()) {
            // R-15-08: a dry run writes only its JobRun, so it takes no lease (E5-T09): it never blocks, nor is blocked by, a real run.
            var running = new JobRun(UUID.randomUUID().toString(), request.clubId(), name, request.scheduledFor(), local(request),
                    request.zone().getId(), request.trigger(), true, JobStatus.RUNNING, null, clock.instant(), null, null,
                    List.of(), List.of(), List.of(), request.actorAccountId(), entries(baseSnapshot(request)), false, holder, false);
            runs.insert(running);
            return Optional.of(run(request, running, lock, holder));
        }
        if (!locks.acquire(lock, holder, now, LEASE)) {
            if (request.trigger() == JobTrigger.MANUAL) { throw new ApiException(ErrorCode.JOB_ALREADY_RUNNING); }
            return skip(request, SkipReason.LOCKED, now);
        }
        try {
            boolean exclusive = !request.dryRun() && request.trigger() != JobTrigger.MANUAL;
            var running = new JobRun(UUID.randomUUID().toString(), request.clubId(), name, request.scheduledFor(), local(request),
                    request.zone().getId(), request.trigger(), request.dryRun(), JobStatus.RUNNING, null, clock.instant(), null, null,
                    List.of(), List.of(), List.of(), request.actorAccountId(), entries(baseSnapshot(request)), exclusive, holder, false);
            try { runs.insert(running); }
            catch (DuplicateKeyException claimed) { return Optional.empty(); }
            return Optional.of(run(request, running, lock, holder));
        } finally {
            locks.release(lock, holder);
        }
    }

    private JobRun run(Request request, JobRun running, String lock, String holder) {
        var recorder = new Recorder(baseSnapshot(request));
        var context = new JobContext(request.clubId(), request.zone(), request.scheduledFor(),
                request.scheduledFor().atZone(request.zone()).toLocalDate(), request.dryRun(), request.config(), recorder, running.id());
        var items = new ArrayList<JobRun.Item>();
        var errors = new ArrayList<JobRun.RunError>();
        JobStatus status;
        Instant renewed = clock.instant();
        boolean leaseLost = false;
        try {
            List<JobItem> plan = request.job().plan(context);
            if (request.dryRun()) {
                for (JobItem item : plan) {
                    recorder.count("WOULD_" + item.action(), 1);
                    // E5-T15 (review E5-T13 #2): the platform part of the plan counter, counted before the trace keeps only
                    // MAX_ITEMS items, so a club's view can leave the whole platform pass out (JobViews.forClub).
                    if (JobViews.platformItem(item.entityType())) { recorder.count(JobViews.platformPlanCounter(item.action()), 1); }
                    trace(items, item.entityType(), item.entityId(), "WOULD_" + item.action(), item.detail());
                }
            } else {
                for (JobItem item : plan) {
                    if (Duration.between(renewed, clock.instant()).compareTo(RENEWAL) >= 0) {
                        renewed = clock.instant();
                        // E5-T10: a holder whose lease was reaped (or taken by a retake) applies nothing more of its stale plan.
                        if (!locks.renew(lock, holder, renewed, LEASE)) { leaseLost = true; break; }
                    }
                    try {
                        JobEffect effect = write(() -> request.job().apply(context, item));
                        effect.counters().forEach(recorder::count);
                        trace(items, item.entityType(), item.entityId(), effect.action() == null ? item.action() : effect.action(),
                                effect.detail().isEmpty() ? item.detail() : effect.detail());
                    } catch (RuntimeException failure) {
                        errors.add(error(item.entityId(), failure));
                        LOG.error("Job item failed jobRunId={} job={} clubId={} entityId={} traceId={}", running.id(), running.job(),
                                request.clubId(), item.entityId(), errors.getLast().traceId(), failure);
                    }
                }
            }
            if (leaseLost) {
                errors.addFirst(new JobRun.RunError(null, ErrorCode.INTERNAL_ERROR.name(), "Lease lost before the run finished",
                        UUID.randomUUID().toString()));
                status = JobStatus.FAILED;
            } else {
                status = errors.isEmpty() ? JobStatus.SUCCEEDED : JobStatus.PARTIAL;
            }
        } catch (RuntimeException failure) {
            errors.addFirst(error(null, failure));
            status = JobStatus.FAILED;
            LOG.error("Job failed jobRunId={} job={} clubId={} traceId={}", running.id(), running.job(), request.clubId(),
                    errors.getFirst().traceId(), failure);
        }
        // A lost lease closes the row like the reaper would (not exclusive), so the next tick can retake the occurrence.
        var finished = running.finished(status, clock.instant(), entries(recorder.counters), items, errors, entries(recorder.snapshot), leaseLost);
        if (!complete(finished)) {
            // A slow holder whose lease was reaped: the reaper's FAILED row stands and the retake owns the occurrence.
            // That row keeps the reaper's counters, so this WARN is the only trace of what the holder really applied (E5-T10).
            LOG.warn("Job finished after its lease was reaped jobRunId={} job={} clubId={} status={} leaseLost={} counters={} items={}",
                    running.id(), running.job(), request.clubId(), status, leaseLost, recorder.counters, items.size());
            return runs.findById(running.id()).orElse(finished);
        }
        metrics.finished(request.clubId(), running.job(), status, Duration.ofMillis(finished.durationMs()), recorder.counters);
        if (status == JobStatus.FAILED && !request.dryRun()) { failed(request.job(), request.clubId(), running.id()); }
        return finished;
    }

    /** {@link Job#failed}: only after the call that closed the row, so a slow holder whose run was reaped never repeats it. */
    private static void failed(Job job, String clubId, String runId) {
        try { job.failed(clubId, runId); }
        catch (RuntimeException failure) {
            LOG.error("Job failure hook failed jobRunId={} job={} clubId={}", runId, job.name(), clubId, failure);
        }
    }

    /** §5 «RUNNING → FAILED: lease caducat»: a run whose holder lost the lease is closed so the next tick can take it as CATCH_UP. */
    private void reap(String clubId, JobName name) {
        for (JobRun run : runs.running(name)) { reap(clubId, run); }
    }

    /** Package-private so a test can replay a stale read (a run that finished between the reaper's read and its write). */
    boolean reap(String clubId, JobRun run) {
        Instant now = clock.instant();
        // A dry run holds no lease: it is dead only once it has been RUNNING for longer than a lease would last.
        if (run.dryRun() ? run.startedAt().plus(LEASE).isAfter(now) : locks.held(clubId + ":" + run.job(), run.holder(), now)) { return false; }
        var error = new JobRun.RunError(null, ErrorCode.INTERNAL_ERROR.name(), "Lease expired before the run finished", UUID.randomUUID().toString());
        var closed = run.finished(JobStatus.FAILED, now, run.counters(), run.items(), List.of(error), run.parametersSnapshot(), true);
        if (!complete(closed)) {
            return false;
        }
        // R-15-10: the reaper's FAILED is the outcome of record, so it is the one counted in jobs.run.duration (E5-T10).
        metrics.finished(clubId, run.job(), JobStatus.FAILED, Duration.ofMillis(closed.durationMs()), Map.of());
        LOG.error("Job lease expired jobRunId={} job={} clubId={}", run.id(), run.job(), clubId);
        if (!run.dryRun()) { registered(run.job()).ifPresent(job -> failed(job, clubId, run.id())); }
        return true;
    }

    /**
     * Trace, SchedulerRun and JobFailed commit together, and only if this call closed the RUNNING row of its holder
     * (R-15-04/R-15-06): a run already closed by the reaper or by its holder publishes nothing. A dry run writes only its JobRun (R-15-08).
     */
    private boolean complete(JobRun finished) {
        if (finished.dryRun()) { return runs.finish(finished); }
        return write(() -> {
            if (!runs.finish(finished)) { return false; }
            events.publish(event(SchedulerEvent.Kind.SchedulerRun, finished, schedulerRunPayload(finished)));
            if (finished.status() == JobStatus.FAILED) { events.publish(failed(finished)); }
            return true;
        });
    }

    private SchedulerEvent failed(JobRun run) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("job", run.job().name()); payload.put("runId", run.id());
        payload.put("status", run.status().name()); payload.put("errorCount", run.errors().size());
        return event(SchedulerEvent.Kind.JobFailed, run, payload);
    }
    private static Map<String, Object> schedulerRunPayload(JobRun run) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("job", run.job().name()); payload.put("clubId", run.clubId()); payload.put("runId", run.id());
        payload.put("scheduledFor", run.scheduledFor().toString()); payload.put("trigger", run.trigger().name());
        payload.put("status", run.status().name()); payload.put("startedAt", run.startedAt().toString());
        payload.put("finishedAt", run.finishedAt().toString());
        var counters = new LinkedHashMap<String, Object>();
        run.counters().forEach(entry -> counters.put(entry.key(), entry.value()));
        payload.put("counters", counters); payload.put("errorCount", run.errors().size());
        return payload;
    }
    private SchedulerEvent event(SchedulerEvent.Kind kind, JobRun run, Map<String, Object> payload) {
        boolean manual = run.trigger() == JobTrigger.MANUAL;
        return new SchedulerEvent(kind, run.clubId(), run.id(), clock.instant(), payload, manual ? run.actorAccountId() : null, null,
                manual ? DomainEvent.Origin.BACKOFFICE : DomainEvent.Origin.SYSTEM);
    }

    /** Retries an aborted Mongo transaction (WriteConflict / TransientTransactionError), never a committed one. */
    private <T> T write(Supplier<T> action) {
        for (int attempt = 0; ; attempt++) {
            try { return transactions.execute(status -> action.get()); }
            catch (RuntimeException failure) {
                if (!retryable(failure) || attempt >= 2) { throw failure; }
            }
        }
    }
    private static boolean retryable(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.mongodb.MongoException mongo && (mongo.getCode() == 112 || mongo.hasErrorLabel("TransientTransactionError"))) { return true; }
        }
        return false;
    }

    private static void trace(List<JobRun.Item> items, String entityType, String entityId, String action, Map<String, Object> detail) {
        if (items.size() < MAX_ITEMS) { items.add(new JobRun.Item(entityType, entityId, action, entries(detail))); }
    }
    private static JobRun.RunError error(String entityId, RuntimeException failure) {
        String code = failure instanceof ApiException api ? api.code().name() : ErrorCode.INTERNAL_ERROR.name();
        String message = failure instanceof ApiException ? code : failure.getClass().getSimpleName();
        return new JobRun.RunError(entityId, code, message, UUID.randomUUID().toString());
    }
    private static Map<String, Object> baseSnapshot(Request request) {
        var snapshot = new LinkedHashMap<String, Object>();
        var definition = request.definition();
        for (String key : new String[] {definition.switchParameter(), definition.localTimeParameter(), definition.dayParameter()}) {
            if (key != null) { snapshot.put(key, request.config().get(key, Object.class)); }
        }
        return snapshot;
    }
    private static List<JobRun.Entry> entries(Map<String, ?> values) {
        var list = new ArrayList<JobRun.Entry>();
        values.forEach((key, value) -> list.add(new JobRun.Entry(key, value)));
        return list;
    }
    private static String local(Request request) { return request.scheduledFor().atZone(request.zone()).toLocalDateTime().format(LOCAL); }

    private static final class Recorder implements JobRunRecorder {
        private final Map<String, Object> snapshot;
        private final Map<String, Long> counters = new LinkedHashMap<>();
        private Recorder(Map<String, Object> snapshot) { this.snapshot = new LinkedHashMap<>(snapshot); }
        @Override public void parameter(String key, Object value) { snapshot.put(key, value); }
        @Override public void count(String key, long delta) {
            // Counters travel in SchedulerRun payload maps; Mongo map keys cannot contain dots.
            if (key.contains(".")) { throw new IllegalArgumentException("Counter keys cannot contain dots: " + key); }
            counters.merge(key, delta, Long::sum);
        }
    }
}
