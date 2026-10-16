package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.CleanupRepository;
import com.agilityhub.core.clubs.common.persistence.ExportJob;
import com.agilityhub.core.clubs.common.persistence.ListExportRepository;
import com.agilityhub.core.clubs.followup.application.AttachmentStorage;
import com.agilityhub.core.clubs.common.application.ports.OutlivedBuildSessionsPort;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.platform.application.PlatformDomains;
import com.agilityhub.core.platform.application.jobs.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * S15 R-15-19 P9 `cleanup` (daily at `jobs.dailyTime`, unlimited catch-up): the only process that deletes documents,
 * and only technical ones. It reports (never deletes) the TTL-managed documents Mongo has not removed yet
 * (`ttlPending*`), deletes orphan signup uploads (`orphanUploadsDeleted`), purges finished exports (`exportsPurged`), and
 * deletes processed `domain_events` (`domainEventsDeleted`), `stripe_events` once S12 creates them (`stripeEventsDeleted`)
 * and `job_runs` except the last five of each process (`jobRunsDeleted`). No business event. Every item is a `DELETE`;
 * a dry run records the same totals as `WOULD_DELETE_*` counters. Counter keys carry no dots (they are Mongo map keys).
 * All cut-offs derive from the occurrence (`scheduledFor`), so the plan and the effects see the same data.
 * <p>
 * Platform pass (E5-T10, organizer ruling of 24-09): the `domain_events` and `job_runs` without a `clubId` follow the
 * same rules (catalog retention, the last five real executions per process kept), once per P9 cycle: the first real
 * run of the UTC day claims it ({@link CleanupRepository#claimPlatformCycle}), whatever the club; if that run ends
 * FAILED the claim is released ({@link #failed}, E5-T13). Counters `platformPass`, `platformDomainEventsDeleted`,
 * `platformJobRunsDeleted`: stored in the JobRun, never shown in the club's run views (AGENTS rule 4, {@code JobViews}).
 * <p>
 * Build sessions (E9-T04, S16 R-16-11 «Una sessió oberta > 12 h es tanca sola (S15 P9 neteja)»), with `COURSES` only: an open
 * session started `courses.buildSessionMaxHours` or more before the occurrence becomes `ABANDONED` (item `ABANDON_BUILD_SESSION`,
 * `WOULD_ABANDON_BUILD_SESSION` in a dry run, counter `buildSessionsAbandoned`). The one item that is not a `DELETE`: the session
 * is a business document and is kept; it is reached through {@link OutlivedBuildSessionsPort} only.
 * <p>
 * Domain re-check (E10-T03, S17 R-17-03 «es reverifica setmanalment (S15 P9)»): the club's `VERIFIED` and `BROKEN` domains whose
 * last re-check is `platform.domainRecheckDays` (7; 0 = off) or more before the occurrence. The plan asks the DNS (outside any
 * transaction) and each item settles one domain (`RECHECK_DOMAIN`, `WOULD_RECHECK_DOMAIN` in a dry run, its detail the outcome
 * and the proof): counters `domainsRechecked`, `domainsBroken` (the third failure in a row; `ClubDomainBroken` → N-43) and
 * `domainsRestored`. Like the build sessions, nothing is deleted; the item goes through {@link PlatformDomains} only.
 */
@Component
public class CleanupJob implements Job {
    static final String DELETE = "DELETE";
    static final String ABANDON = "ABANDON_BUILD_SESSION";
    static final String RECHECK = "RECHECK_DOMAIN";
    static final int KEEP_RUNS = 5;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CleanupJob.class);
    /** The `idempotency_records` TTL (`IdempotencyRepository`, 24 h): older records are only waiting for Mongo's TTL monitor. */
    static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private final CleanupRepository cleanup; private final ListExportRepository exports; private final ExportStorage exportFiles;
    private final AttachmentStorage uploads; private final ParameterCatalog parameters; private final OutlivedBuildSessionsPort buildSessions;
    private final PlatformDomains domains;
    public CleanupJob(CleanupRepository cleanup, ListExportRepository exports, ExportStorage exportFiles, AttachmentStorage uploads, ParameterCatalog parameters,
            OutlivedBuildSessionsPort buildSessions, PlatformDomains domains) {
        this.cleanup = cleanup; this.exports = exports; this.exportFiles = exportFiles; this.uploads = uploads; this.parameters = parameters;
        this.buildSessions = buildSessions; this.domains = domains;
    }
    /** Platform rows belong to no club, so their retention is the catalog's product value, never a club's (R-15-19). */
    private record PlatformCutoffs(Instant events, Instant runs) { }
    private PlatformCutoffs platformCutoffs(JobContext context) {
        Instant now = context.scheduledFor();
        return new PlatformCutoffs(now.minus(Duration.ofDays(parameters.defaultInteger("jobs.retention.domainEventsDays"))),
                now.minus(Duration.ofDays(parameters.defaultInteger("jobs.retention.jobRunsDays"))));
    }
    @Override public JobName name() { return JobName.CLEANUP; }

    private record Cutoffs(Instant uploads, Instant exports, Instant events, Instant stripe, Instant runs) { }
    private static Cutoffs cutoffs(JobContext context) {
        Instant now = context.scheduledFor();
        return new Cutoffs(now.minus(Duration.ofHours(context.parameter("jobs.retention.orphanUploadsHours", Integer.class))),
                now.minus(Duration.ofDays(context.parameter("jobs.retention.exportFilesDays", Integer.class))),
                now.minus(Duration.ofDays(context.parameter("jobs.retention.domainEventsDays", Integer.class))),
                now.minus(Duration.ofDays(context.parameter("jobs.retention.stripeEventsDays", Integer.class))),
                now.minus(Duration.ofDays(context.parameter("jobs.retention.jobRunsDays", Integer.class))));
    }

    @Override public List<JobItem> plan(JobContext context) {
        String club = context.clubId(); Instant now = context.scheduledFor(); var cut = cutoffs(context); var recorder = context.recorder();
        recorder.count("ttlPendingSeatHolds", cleanup.ttlPending(club, "seat_holds", "expiresAt", now));
        recorder.count("ttlPendingMagicLinkTokens", cleanup.ttlPending(club, "magic_link_tokens", "expiresAt", now));
        recorder.count("ttlPendingIdempotencyRecords", cleanup.ttlPending(club, "idempotency_records", "createdAt", now.minus(IDEMPOTENCY_TTL)));
        recorder.count("ttlPendingJobLocks", cleanup.ttlPending(club, "job_locks", "expiresAt", now));
        // R-15-19 (amended 25-09, E3-T16): the recipient-cap decisions past their event's retention. One whose event may still
        // retry has no `expiresAt` (E3-T15), so it is never counted.
        recorder.count("ttlPendingSignupNotificationAdmissions", cleanup.ttlPending(club, "signup_notification_admissions", "expiresAt", now));
        // R-15-19 (amended 25-09, E5-T17): the ring-slot sequences, kept until `startsAt` + 7 days.
        recorder.count("ttlPendingRingSlotLocks", cleanup.ttlPending(club, "ring_slot_locks", "expiresAt", now));
        var items = new ArrayList<JobItem>();
        var totals = new LinkedHashMap<String, Long>();
        var orphans = cleanup.orphanUploads(club, cut.uploads());
        orphans.forEach(key -> items.add(new JobItem("SignupUpload", key, DELETE)));
        totals.put("orphanUploads", (long) orphans.size());
        var purgeable = exports.purgeable(cut.exports());
        purgeable.forEach(job -> items.add(new JobItem("ExportJob", job.id(), DELETE)));
        totals.put("exports", (long) purgeable.size());
        long events = cleanup.processedEvents(club, cut.events());
        if (events > 0) { items.add(new JobItem("DomainEvents", CleanupRepository.DOMAIN_EVENTS, DELETE, Map.of("count", events))); }
        totals.put("domainEvents", events);
        // Checked here, outside any transaction (listCollections is not allowed inside one); absent before E8.
        long stripe = cleanup.stripeEventsExist() ? cleanup.stripeEvents(club, cut.stripe()) : 0;
        if (stripe > 0) { items.add(new JobItem("StripeEvents", CleanupRepository.STRIPE_EVENTS, DELETE, Map.of("count", stripe))); }
        totals.put("stripeEvents", stripe);
        long runs = 0;
        for (JobName job : JobName.values()) {
            int expired = cleanup.expiredRuns(club, job.name(), cut.runs(), KEEP_RUNS).size();
            if (expired > 0) { items.add(new JobItem("JobRuns", job.name(), DELETE, Map.of("count", (long) expired))); runs += expired; }
        }
        totals.put("jobRuns", runs);
        if (context.config().modules().contains(Module.COURSES)) {
            // The cut-off is the parameter's, read (and recorded in the run's snapshot) here; the adapter applies the same one.
            context.parameter("courses.buildSessionMaxHours", Integer.class);
            buildSessions.outlived(now).forEach(id -> items.add(new JobItem("BuildSession", id, ABANDON)));
        }
        int recheckDays = context.parameter("platform.domainRecheckDays", Integer.class);
        for (var recheck : domains.recheckPlan(now, recheckDays)) {
            var detail = new LinkedHashMap<String, Object>(); detail.put("outcome", recheck.outcome()); detail.put("passed", recheck.passed());
            if (recheck.method() != null) { detail.put("method", recheck.method()); }
            detail.put("failedChecks", recheck.passed() ? 0 : recheck.failedChecks() + 1);
            items.add(new JobItem("ClubDomain", recheck.host(), RECHECK, detail));
        }
        // A dry run only looks (a real run of this occurrence would still get the pass); a real run claims the cycle.
        boolean platformPass = context.dryRun() ? cleanup.platformCycleOpen(now) : cleanup.claimPlatformCycle(club, now, context.runId());
        recorder.count("platformPass", platformPass ? 1 : 0);
        if (platformPass) { totals.putAll(platformItems(context, items)); }
        if (context.dryRun()) { totals.forEach((key, value) -> recorder.count("WOULD_DELETE_" + key, value)); }
        else {
            for (String key : List.of("orphanUploadsDeleted", "exportsPurged", "domainEventsDeleted", "stripeEventsDeleted", "jobRunsDeleted",
                    "platformDomainEventsDeleted", "platformJobRunsDeleted")) { recorder.count(key, 0); }
            if (context.config().modules().contains(Module.COURSES)) { recorder.count("buildSessionsAbandoned", 0); }
            if (recheckDays > 0) { for (String key : List.of("domainsRechecked", "domainsBroken", "domainsRestored")) { recorder.count(key, 0); } }
        }
        return items;
    }
    /** E5-T13: a FAILED run gives the platform cycle back (if it had claimed it), so another run of the day can do the pass. */
    @Override public void failed(String clubId, String runId) {
        if (cleanup.releasePlatformCycle(clubId, runId)) {
            LOG.warn("Cleanup platform cycle released by a failed run jobRunId={} clubId={}", runId, clubId);
        }
    }
    private Map<String, Long> platformItems(JobContext context, List<JobItem> items) {
        var cut = platformCutoffs(context); var totals = new LinkedHashMap<String, Long>();
        long events = cleanup.processedPlatformEvents(cut.events());
        if (events > 0) { items.add(new JobItem("PlatformDomainEvents", CleanupRepository.DOMAIN_EVENTS, DELETE, Map.of("count", events))); }
        totals.put("platformDomainEvents", events);
        long runs = 0;
        for (JobName job : JobName.values()) {
            int expired = cleanup.expiredPlatformRuns(job.name(), cut.runs(), KEEP_RUNS).size();
            if (expired > 0) { items.add(new JobItem("PlatformJobRuns", job.name(), DELETE, Map.of("count", (long) expired))); runs += expired; }
        }
        totals.put("platformJobRuns", runs);
        return totals;
    }

    @Override public JobEffect apply(JobContext context, JobItem item) {
        String club = context.clubId(); var cut = cutoffs(context);
        return switch (item.entityType()) {
            case "SignupUpload" -> {
                uploads.delete(item.entityId());
                yield counted(item, "orphanUploadsDeleted", cleanup.deleteUpload(club, item.entityId()) ? 1 : 0);
            }
            case "ExportJob" -> {
                var job = exports.findById(item.entityId()).orElse(null);
                if (job == null) { yield counted(item, "exportsPurged", 0); }
                files(job).forEach(exportFiles::delete);
                yield counted(item, "exportsPurged", exports.purged(job, context.scheduledFor()) ? 1 : 0);
            }
            case "DomainEvents" -> counted(item, "domainEventsDeleted", cleanup.deleteProcessedEvents(club, cut.events()));
            case "StripeEvents" -> counted(item, "stripeEventsDeleted", cleanup.deleteStripeEvents(club, cut.stripe()));
            case "JobRuns" -> counted(item, "jobRunsDeleted", cleanup.deleteRuns(club, cleanup.expiredRuns(club, item.entityId(), cut.runs(), KEEP_RUNS)));
            case "PlatformDomainEvents" -> counted(item, "platformDomainEventsDeleted", cleanup.deleteProcessedPlatformEvents(platformCutoffs(context).events()));
            case "BuildSession" -> {
                long abandoned = buildSessions.abandon(item.entityId(), context.scheduledFor()) ? 1 : 0;
                yield new JobEffect(ABANDON, Map.of(), Map.of("buildSessionsAbandoned", abandoned));
            }
            case "ClubDomain" -> {
                String outcome = domains.recheck(item.entityId(), Boolean.TRUE.equals(item.detail().get("passed")), (String) item.detail().get("method"),
                        context.scheduledFor());
                var detail = new LinkedHashMap<String, Object>(item.detail()); detail.put("outcome", outcome);
                yield new JobEffect(RECHECK, detail, Map.of("domainsRechecked", outcome.equals(PlatformDomains.SKIPPED) ? 0L : 1L,
                        "domainsBroken", outcome.equals(PlatformDomains.BROKEN) ? 1L : 0L, "domainsRestored", outcome.equals(PlatformDomains.RESTORED) ? 1L : 0L));
            }
            case "PlatformJobRuns" -> counted(item, "platformJobRunsDeleted",
                    cleanup.deletePlatformRuns(cleanup.expiredPlatformRuns(item.entityId(), platformCutoffs(context).runs(), KEEP_RUNS)));
            default -> throw new IllegalArgumentException("Unknown cleanup item " + item.entityType());
        };
    }
    private static JobEffect counted(JobItem item, String counter, long count) {
        var detail = item.detail().containsKey("count") ? Map.<String, Object>of("count", count) : Map.<String, Object>of();
        return new JobEffect(DELETE, detail, Map.of(counter, count));
    }
    private static Set<String> files(ExportJob job) {
        var keys = new LinkedHashSet<String>();
        if (job.fileKeys() != null) { keys.addAll(job.fileKeys()); }
        if (job.fileKey() != null) { keys.add(job.fileKey()); }
        return keys;
    }
}
