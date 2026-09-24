package com.agilityhub.core.platform.persistence.jobs;

import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S15 §3 trace of one execution, also SKIPPED ones and dry runs. Counters and the parameter snapshot are
 * stored as key/value lists because their keys contain dots (`classes.minDogs`, `ttlPending.seat_holds`).
 * `exclusive` marks the rows that claim an occurrence (non-dry SCHEDULE/CATCH_UP runs that execute): the unique
 * index `{clubId, job, scheduledFor, trigger}` applies to them only, so SKIPPED{LOCKED}, dry runs and manual runs
 * never collide with the claim. `holder` identifies the lease; `leaseExpired` marks a run reaped after its process died.
 */
@Document("job_runs")
public record JobRun(@Id String id, String clubId, JobName job, Instant scheduledFor, String scheduledForLocal, String timeZone,
        JobTrigger trigger, boolean dryRun, JobStatus status, SkipReason skipReason, Instant startedAt, Instant finishedAt, Long durationMs,
        List<Entry> counters, List<Item> items, List<RunError> errors, String actorAccountId, List<Entry> parametersSnapshot,
        boolean exclusive, String holder, boolean leaseExpired) implements TenantEntity {
    public record Entry(String key, Object value) { }
    public record Item(String entityType, String entityId, String action, List<Entry> detail) { }
    public record RunError(String entityId, String code, String message, String traceId) { }

    /** `expired` = reaped after its lease expired: the row gives up its claim so the retake (also CATCH_UP) can take the slot. */
    public JobRun finished(JobStatus next, Instant at, List<Entry> nextCounters, List<Item> nextItems, List<RunError> nextErrors,
            List<Entry> snapshot, boolean expired) {
        return new JobRun(id, clubId, job, scheduledFor, scheduledForLocal, timeZone, trigger, dryRun, next, skipReason, startedAt, at,
                at.toEpochMilli() - startedAt.toEpochMilli(), nextCounters, nextItems, nextErrors, actorAccountId, snapshot,
                exclusive && !expired, holder, expired);
    }
}
