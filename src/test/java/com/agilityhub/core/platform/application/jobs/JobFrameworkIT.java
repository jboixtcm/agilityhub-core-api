package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.clubs.common.application.JobFailureNotifications;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.Parameter;
import com.agilityhub.core.platform.persistence.jobs.JobLockRepository;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

/** S15 WP-15-A framework end to end with the fictitious TEST_NOOP job (T-15-03, 05, 06, 10, 31 lease part, R-15-09 audit). */
class JobFrameworkIT extends AbstractIntegrationTest {
    static final String CLUB = "e5-jobs-a", SUSPENDED = "e5-jobs-b", ADMIN = "e5-jobs-admin";
    static final JobDefinition DAILY = new JobDefinition(JobName.TEST_NOOP, "test-noop", Cadence.DAILY, "jobs.dailyTime", null, null,
            CatchUpWindow.UNLIMITED, "jobs.cleanup.enabled");
    static final JobDefinition CONTINUOUS = new JobDefinition(JobName.TEST_NOOP, "test-noop", Cadence.CONTINUOUS, null, null, null,
            CatchUpWindow.CONTINUOUS, "jobs.cleanup.enabled");
    @Autowired JobRunner runner;
    @Autowired SchedulerTick tick;
    @Autowired TestNoopJob job;
    @Autowired JobTriggerService triggers;
    @Autowired JobLockRepository locks;
    @Autowired JobFailureNotifications alerts;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired ObjectMapper mapper;
    @Autowired MeterRegistry metrics;

    static Instant at(String value) { return Instant.parse(value); }

    @BeforeEach void prepare() {
        job.reset();
        mongo.remove(Query.query(Criteria.where("_id").in(CLUB, SUSPENDED)), Club.class);
        for (String collection : List.of("job_runs", "domain_events", "audit_entries", "notifications", "parameters", "memberships")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, SUSPENDED)), collection);
        }
        mongo.remove(new Query(), "job_locks");
        for (String id : List.of(CLUB, SUSPENDED)) {
            var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(id, id + ".example.test"));
            tree.set("modules", mapper.valueToTree(List.of(Module.BILLING, Module.FREE_TRAINING)));
            tree.put("status", id.equals(CLUB) ? "ACTIVE" : "SUSPENDED");
            clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(id);
        }
        mongo.save(new Account(ADMIN, "e5-jobs-admin@example.test", "Example Admin", "ca", null, Set.of(), Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
        mongo.save(new Membership(ADMIN, ADMIN, CLUB, null, Set.of(Role.ADMIN), Membership.Status.ACTIVE, Role.ADMIN));
        clock.setInstant(at("2026-10-05T04:00:00Z"));
    }
    @AfterEach void clean() { SecurityContextHolder.clearContext(); job.reset(); }

    private void parameter(String key, Object value) {
        mongo.remove(Query.query(Criteria.where("_id").is(CLUB + ":" + key)), Parameter.class);
        mongo.insert(new Parameter(CLUB + ":" + key, CLUB, key, value, "club", "club", null, List.of(), 0L, clock.instant()));
        configs.invalidate(CLUB);
    }
    private List<Document> runs() {
        // Only the fictitious job: since E5-T05 the tick also runs the five real E5 processes for every club.
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("job").is("TEST_NOOP")).with(org.springframework.data.domain.Sort.by("startedAt", "_id")), Document.class, "job_runs");
    }
    private List<Document> events(String type) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("type").is(type))
                .with(org.springframework.data.domain.Sort.by("occurredAt", "_id")), Document.class, "domain_events");
    }
    private Map<String, Long> counts() {
        var result = new TreeMap<String, Long>();
        for (String name : mongo.getCollectionNames()) { result.put(name, mongo.getCollection(name).countDocuments()); }
        return result;
    }

    @Test void T_15_03_anOccurrenceRunsOnceAcrossTicksInstancesAndTheUniqueIndex() {
        job.configure(DAILY, 2, 0, false);
        var first = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(first.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(first.trigger()).isEqualTo(JobTrigger.SCHEDULE);
        assertThat(first.scheduledFor()).isEqualTo(at("2026-10-05T04:00:00Z"));
        assertThat(first.scheduledForLocal()).isEqualTo("2026-10-05T06:00");
        assertThat(first.timeZone()).isEqualTo("Europe/Madrid");
        assertThat(first.exclusive()).isTrue();
        assertThat(job.applied()).isEqualTo(2);
        // Three minutes later the occurrence is NOT_DUE: no new JobRun.
        assertThat(runner.scheduled(CLUB, true, job, at("2026-10-05T04:03:00Z"))).isEmpty();
        assertThat(runs()).hasSize(1);
        // Another instance holds the lease of the next occurrence → SKIPPED{LOCKED}, which does not claim it.
        clock.setInstant(at("2026-10-06T04:00:00Z"));
        assertThat(locks.acquire(CLUB + ":TEST_NOOP", "other-instance", clock.instant(), Duration.ofSeconds(300))).isTrue();
        var locked = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(locked.status()).isEqualTo(JobStatus.SKIPPED);
        assertThat(locked.skipReason()).isEqualTo(SkipReason.LOCKED);
        assertThat(locked.exclusive()).isFalse();
        locks.release(CLUB + ":TEST_NOOP", "other-instance");
        assertThat(runner.scheduled(CLUB, true, job, at("2026-10-06T04:01:00Z")).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        // The unique index is the last guard: a second exclusive row of the same occurrence and trigger is refused.
        var claimed = mongo.findById(first.id(), JobRun.class);
        assertThatThrownBy(() -> mongo.insert(new JobRun("duplicate", CLUB, JobName.TEST_NOOP, claimed.scheduledFor(), claimed.scheduledForLocal(),
                claimed.timeZone(), JobTrigger.SCHEDULE, false, JobStatus.RUNNING, null, clock.instant(), null, null, List.of(), List.of(), List.of(),
                null, List.of(), true, "other", false))).isInstanceOf(DuplicateKeyException.class);
        // A claim whose holder died (RUNNING, no live lease) is reaped and gives its slot back: the retake of the same
        // occurrence and trigger runs instead of losing silently on the unique index, and releases its lease.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(first.id())), new org.springframework.data.mongodb.core.query.Update()
                .set("status", JobStatus.RUNNING).set("holder", "dead-holder").unset("finishedAt"), JobRun.class);
        mongo.remove(Query.query(Criteria.where("scheduledFor").is(at("2026-10-06T04:00:00Z"))), JobRun.class);
        clock.setInstant(at("2026-10-05T04:01:00Z"));
        var retake = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(retake.trigger()).isEqualTo(JobTrigger.SCHEDULE);
        assertThat(retake.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(retake.exclusive()).isTrue();
        var reaped = mongo.findById(first.id(), JobRun.class);
        assertThat(reaped.status()).isEqualTo(JobStatus.FAILED);
        assertThat(reaped.leaseExpired()).isTrue();
        assertThat(reaped.exclusive()).isFalse();
        assertThat(mongo.findById(CLUB + ":TEST_NOOP", Document.class, "job_locks")).isNull();
    }

    @Test void T_15_31_aRunThatFinishesBetweenTheReapersReadAndWriteStaysSucceeded() {
        job.configure(DAILY, 1, 0, false);
        var stale = new AtomicReference<JobRun>();
        // The reaper's read: the row as it was while the run was still RUNNING.
        job.duringNextApply(() -> stale.set(mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("job").is("TEST_NOOP")
                .and("status").is("RUNNING")), JobRun.class)));
        var finished = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(finished.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(stale.get()).isNotNull().extracting(JobRun::id).isEqualTo(finished.id());
        // The holder has finished and released its lease; the reaper now writes from its stale read and changes nothing.
        clock.setInstant(at("2026-10-05T04:10:00Z"));
        try (var scope = TenantContext.open(CLUB)) { assertThat(runner.reap(CLUB, stale.get())).isFalse(); }
        var stored = mongo.findById(finished.id(), JobRun.class);
        assertThat(stored.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(stored.leaseExpired()).isFalse();
        assertThat(stored.exclusive()).isTrue();
        assertThat(events("JobFailed")).isEmpty();
        assertThat(events("SchedulerRun")).singleElement()
                .satisfies(event -> assertThat(((Document) event.get("payload"))).containsEntry("status", "SUCCEEDED"));
    }

    @Test void T_15_31_aSlowCatchUpHolderReapedMidRunDoesNotOverwriteAndIsRetakenAsCatchUp() throws Exception {
        job.configure(DAILY, 1, 0, false);
        // Three minutes late the 06:00 occurrence runs as CATCH_UP; its lease ends at 04:08.
        clock.setInstant(at("2026-10-05T04:03:00Z"));
        var retake = new AtomicReference<JobRun>();
        job.duringNextApply(() -> {
            // While the slow holder is still inside apply, another instance ticks after the lease expired.
            var other = Executors.newSingleThreadExecutor();
            try {
                clock.setInstant(at("2026-10-05T04:09:00Z"));
                retake.set(other.submit(() -> runner.scheduled(CLUB, true, job, at("2026-10-05T04:09:00Z")).orElseThrow()).get());
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            } finally { other.shutdownNow(); }
        });
        var slow = runner.scheduled(CLUB, true, job, at("2026-10-05T04:03:00Z")).orElseThrow();
        // The reaper's FAILED row stands: the slow holder's completion does not overwrite it.
        assertThat(slow.trigger()).isEqualTo(JobTrigger.CATCH_UP);
        assertThat(slow.status()).isEqualTo(JobStatus.FAILED);
        assertThat(slow.leaseExpired()).isTrue();
        assertThat(slow.exclusive()).isFalse();
        assertThat(mongo.findById(slow.id(), JobRun.class)).isEqualTo(slow);
        // The dead CATCH_UP claim freed its slot, so the retake of the same occurrence is a CATCH_UP too.
        assertThat(retake.get().trigger()).isEqualTo(JobTrigger.CATCH_UP);
        assertThat(retake.get().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(retake.get().scheduledFor()).isEqualTo(at("2026-10-05T04:00:00Z"));
        assertThat(retake.get().exclusive()).isTrue();
        assertThat(job.applied()).isEqualTo(2);
        // Events only from the writes that closed a RUNNING row: the reaper (FAILED) and the retake (SUCCEEDED).
        assertThat(events("JobFailed")).singleElement()
                .satisfies(event -> assertThat(((Document) event.get("payload"))).containsEntry("runId", slow.id()));
        assertThat(events("SchedulerRun")).extracting(event -> ((Document) event.get("payload")).getString("runId"))
                .containsExactlyInAnyOrder(slow.id(), retake.get().id());
        // The next tick finds the occurrence taken by the retake.
        assertThat(runner.scheduled(CLUB, true, job, at("2026-10-05T04:10:00Z"))).isEmpty();
        assertThat(mongo.findById(CLUB + ":TEST_NOOP", Document.class, "job_locks")).isNull();
    }

    @Test void T_15_31_aReapedHolderAppliesNoFurtherItemOfItsPlan() throws Exception {
        job.configure(DAILY, 2, 0, false);
        clock.setInstant(at("2026-10-05T04:03:00Z"));
        var failedTimer = metrics.find("jobs.run.duration").tags("job", "TEST_NOOP", "status", "FAILED").timer();
        long failedBefore = failedTimer == null ? 0 : failedTimer.count();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JobRunner.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        var retake = new AtomicReference<JobRun>();
        String slowThread = Thread.currentThread().getName();
        job.duringNextApply(() -> {
            // While the slow holder applies item-1, its lease expires and another instance reaps and retakes the occurrence.
            var other = Executors.newSingleThreadExecutor();
            try {
                clock.setInstant(at("2026-10-05T04:09:00Z"));
                retake.set(other.submit(() -> runner.scheduled(CLUB, true, job, at("2026-10-05T04:09:00Z")).orElseThrow()).get());
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            } finally { other.shutdownNow(); }
        });
        try {
            var slow = runner.scheduled(CLUB, true, job, at("2026-10-05T04:03:00Z")).orElseThrow();
            // The renewal before item-2 fails: the slow holder stops, and the reaper's FAILED row stands untouched.
            assertThat(job.appliedBy()).filteredOn(entry -> entry.startsWith(slowThread + "/")).containsExactly(slowThread + "/item-1");
            assertThat(job.appliedBy()).filteredOn(entry -> !entry.startsWith(slowThread + "/")).hasSize(2);
            assertThat(job.applied()).isEqualTo(3);
            assertThat(slow.status()).isEqualTo(JobStatus.FAILED);
            assertThat(slow.leaseExpired()).isTrue();
            assertThat(slow.errors()).singleElement().extracting(JobRun.RunError::message).isEqualTo("Lease expired before the run finished");
            assertThat(mongo.findById(slow.id(), JobRun.class)).isEqualTo(slow);
            assertThat(retake.get().status()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(events("JobFailed")).singleElement()
                    .satisfies(event -> assertThat(((Document) event.get("payload"))).containsEntry("runId", slow.id()));
            // The reaper's FAILED is the one counted (E5-T01 review #3); the WARN keeps the holder's local counters (#4).
            assertThat(metrics.find("jobs.run.duration").tags("job", "TEST_NOOP", "status", "FAILED").timer().count()).isEqualTo(failedBefore + 1);
            assertThat(appender.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .singleElement().extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).asString()
                    .contains("jobRunId=" + slow.id()).contains("leaseLost=true").contains("counters={applied=1}").contains("items=1");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void T_15_05_switchModuleAndClubStatusRecordSkippedRunsOncePerOccurrenceOrHour() {
        job.configure(DAILY, 1, 0, false);
        parameter("jobs.cleanup.enabled", false);
        var disabled = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(disabled.skipReason()).isEqualTo(SkipReason.DISABLED);
        assertThat(disabled.parametersSnapshot()).contains(new JobRun.Entry("jobs.cleanup.enabled", false), new JobRun.Entry("jobs.dailyTime", "06:00"));
        assertThat(runner.scheduled(CLUB, true, job, at("2026-10-05T05:00:00Z"))).isEmpty();
        assertThat(job.applied()).isZero();
        // The switch stops the calendar, not the admin (R-15-09).
        assertThat(runner.manual(CLUB, JobName.TEST_NOOP, false, ADMIN).status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.applied()).isEqualTo(1);
        // Continuous processes record a SKIPPED at most once per hour.
        job.configure(CONTINUOUS, 0, 0, false);
        clock.setInstant(at("2026-10-05T06:00:00Z"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow().skipReason()).isEqualTo(SkipReason.DISABLED);
        assertThat(runner.scheduled(CLUB, true, job, at("2026-10-05T06:01:00Z"))).isEmpty();
        assertThat(runner.scheduled(CLUB, true, job, at("2026-10-05T06:59:00Z"))).isEmpty();
        assertThat(runner.scheduled(CLUB, true, job, at("2026-10-05T07:01:00Z"))).isPresent();
        parameter("jobs.cleanup.enabled", true);
        assertThat(runner.scheduled(CLUB, true, job, at("2026-10-05T07:02:00Z")).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        // Module off → MODULE_OFF on schedule and 404 MODULE_DISABLED on demand.
        job.configure(new JobDefinition(JobName.TEST_NOOP, "test-noop", Cadence.DAILY, "jobs.dailyTime", null, Module.WAITLIST,
                CatchUpWindow.UNLIMITED, "jobs.cleanup.enabled"), 1, 0, false);
        clock.setInstant(at("2026-10-06T04:00:00Z"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow().skipReason()).isEqualTo(SkipReason.MODULE_OFF);
        assertThatThrownBy(() -> runner.manual(CLUB, JobName.TEST_NOOP, false, ADMIN))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.MODULE_DISABLED));
        // A suspended club records CLUB_INACTIVE.
        job.configure(DAILY, 1, 0, false);
        clock.setInstant(at("2026-10-07T04:00:00Z"));
        assertThat(runner.scheduled(CLUB, false, job, clock.instant()).orElseThrow().skipReason()).isEqualTo(SkipReason.CLUB_INACTIVE);
        // A new parameter value applies on the next tick and never resurrects yesterday's occurrence at the new hour.
        parameter("jobs.dailyTime", "07:00");
        assertThat(runner.scheduled(CLUB, true, job, at("2026-10-07T04:30:00Z"))).isEmpty();
        var moved = runner.scheduled(CLUB, true, job, at("2026-10-08T05:00:00Z")).orElseThrow();
        assertThat(moved.scheduledFor()).isEqualTo(at("2026-10-08T05:00:00Z"));
        assertThat(moved.parametersSnapshot()).contains(new JobRun.Entry("jobs.dailyTime", "07:00"));
    }

    @Test void T_15_06_dryRunRecordsOnlyThePlanAndThePlanMatchesTheRealEffects() {
        job.configure(DAILY, 3, 0, false);
        var before = counts();
        var lockWrites = new AtomicReference<Document>();
        // E5-T09 (E5-T01 review #7): a dry run takes no lease, so not even `job_locks` is written while it plans.
        job.duringNextPlan(() -> lockWrites.set(mongo.findById(CLUB + ":TEST_NOOP", Document.class, "job_locks")));
        var dry = runner.manual(CLUB, JobName.TEST_NOOP, true, ADMIN);
        assertThat(lockWrites.get()).isNull();
        var after = counts();
        assertThat(after.get("job_runs")).isEqualTo(before.getOrDefault("job_runs", 0L) + 1);
        after.remove("job_runs"); before.remove("job_runs");
        assertThat(after).isEqualTo(before);
        // A real run holding the lease neither blocks a dry run nor is blocked by it.
        assertThat(locks.acquire(CLUB + ":TEST_NOOP", "other-instance", clock.instant(), Duration.ofSeconds(300))).isTrue();
        assertThat(runner.manual(CLUB, JobName.TEST_NOOP, true, ADMIN).status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(mongo.findById(CLUB + ":TEST_NOOP", Document.class, "job_locks").getString("holder")).isEqualTo("other-instance");
        locks.release(CLUB + ":TEST_NOOP", "other-instance");
        // A dry run left RUNNING by a dead process is reaped only once a lease would have expired.
        var stale = new JobRun("dead-dry-run", CLUB, JobName.TEST_NOOP, clock.instant(), "2026-10-05T06:00", "Europe/Madrid", JobTrigger.MANUAL, true,
                JobStatus.RUNNING, null, clock.instant(), null, null, List.of(), List.of(), List.of(), ADMIN, List.of(), false, "dead-holder", false);
        mongo.insert(stale);
        try (var scope = TenantContext.open(CLUB)) {
            assertThat(runner.reap(CLUB, stale)).isFalse();
            clock.setInstant(clock.instant().plus(JobRunner.LEASE).plusSeconds(1));
            assertThat(runner.reap(CLUB, stale)).isTrue();
        }
        assertThat(mongo.findById("dead-dry-run", JobRun.class).status()).isEqualTo(JobStatus.FAILED);
        assertThat(events("JobFailed")).isEmpty();
        clock.setInstant(at("2026-10-05T04:00:00Z"));
        assertThat(dry.dryRun()).isTrue();
        assertThat(dry.trigger()).isEqualTo(JobTrigger.MANUAL);
        assertThat(dry.actorAccountId()).isEqualTo(ADMIN);
        assertThat(dry.items()).extracting(JobRun.Item::action).containsOnly("WOULD_NOOP");
        assertThat(dry.counters()).containsExactly(new JobRun.Entry("WOULD_NOOP", 3L));
        assertThat(job.applied()).isZero();
        assertThat(events("SchedulerRun")).isEmpty();
        var real = runner.manual(CLUB, JobName.TEST_NOOP, false, ADMIN);
        assertThat(real.items()).extracting(JobRun.Item::entityId).containsExactlyElementsOf(dry.items().stream().map(JobRun.Item::entityId).toList());
        assertThat(real.items()).extracting(JobRun.Item::action).containsOnly("NOOP");
        assertThat(real.items().getFirst().detail()).contains(new JobRun.Entry("position", 1));
        assertThat(real.counters()).containsExactly(new JobRun.Entry("applied", 3L));
        assertThat(events("SchedulerRun")).singleElement().satisfies(event -> {
            assertThat(event.getString("origin")).isEqualTo("BACKOFFICE");
            assertThat(event.getString("actorAccountId")).isEqualTo(ADMIN);
            assertThat(((Document) event.get("payload")).getString("trigger")).isEqualTo("MANUAL");
        });
    }

    @Test void T_15_10_itemFailuresArePartialJobFailuresAlertAdminsOncePerLocalDay() throws Exception {
        job.configure(DAILY, 3, 2, false);
        var partial = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(partial.status()).isEqualTo(JobStatus.PARTIAL);
        assertThat(partial.errors()).singleElement().satisfies(error -> {
            assertThat(error.entityId()).isEqualTo("item-2"); assertThat(error.code()).isEqualTo("INVALID_STATE"); assertThat(error.traceId()).isNotBlank();
        });
        assertThat(partial.items()).extracting(JobRun.Item::entityId).containsExactly("item-1", "item-3");
        assertThat(job.applied()).isEqualTo(2);
        assertThat(events("SchedulerRun")).hasSize(1);
        assertThat(events("JobFailed")).isEmpty();
        job.configure(DAILY, 3, 0, true);
        var failed = runner.manual(CLUB, JobName.TEST_NOOP, false, ADMIN);
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.errors().getFirst().entityId()).isNull();
        assertThat(failed.errors().getFirst().code()).isEqualTo("INTERNAL_ERROR");
        clock.setInstant(at("2026-10-05T09:00:00Z"));
        runner.manual(CLUB, JobName.TEST_NOOP, false, ADMIN);
        var jobFailed = events("JobFailed");
        assertThat(jobFailed).hasSize(2);
        assertThat(((Document) jobFailed.getFirst().get("payload"))).containsEntry("job", "TEST_NOOP").containsEntry("status", "FAILED").containsEntry("errorCount", 1);
        for (var event : jobFailed) { alerts.deliver(mapper.readValue(event.getString("eventJson"), SchedulerEvent.class)); }
        var n42 = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-42")), Document.class, "notifications");
        assertThat(n42).extracting(n -> n.getString("channel")).containsExactlyInAnyOrder("APP", "EMAIL");
        // A failure on the next local day alerts again; with jobs.alertAdminsOnFailure=false nothing is sent.
        clock.setInstant(at("2026-10-05T22:30:00Z"));
        runner.manual(CLUB, JobName.TEST_NOOP, false, ADMIN);
        alerts.deliver(mapper.readValue(events("JobFailed").getLast().getString("eventJson"), SchedulerEvent.class));
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-42")), "notifications")).isEqualTo(4);
        parameter("jobs.alertAdminsOnFailure", false);
        clock.setInstant(at("2026-10-06T22:30:00Z"));
        runner.manual(CLUB, JobName.TEST_NOOP, false, ADMIN);
        alerts.deliver(mapper.readValue(events("JobFailed").getLast().getString("eventJson"), SchedulerEvent.class));
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-42")), "notifications")).isEqualTo(4);
        // Micrometer names of R-15-10.
        assertThat(metrics.find("jobs.run.duration").tags("job", "TEST_NOOP", "status", "PARTIAL").timer()).isNotNull();
        assertThat(metrics.find("jobs.run.duration").tags("job", "TEST_NOOP", "status", "FAILED").timer().count()).isGreaterThanOrEqualTo(3);
        assertThat(metrics.find("jobs.effects").tags("job", "TEST_NOOP", "key", "applied").counter().count()).isGreaterThanOrEqualTo(2);
        job.configure(DAILY, 1, 0, false);
        runner.manual(CLUB, JobName.TEST_NOOP, false, ADMIN);
        assertThat(metrics.find("jobs.last_success_age_seconds").tags("job", "TEST_NOOP", "club", CLUB).gauge().value()).isZero();
    }

    @Test void T_15_05_missedWindowSkipsAndAlerts() {
        job.configure(new JobDefinition(JobName.TEST_NOOP, "test-noop", Cadence.DAILY, "jobs.dailyTime", null, null,
                CatchUpWindow.END_OF_LOCAL_DAY, "jobs.cleanup.enabled"), 1, 0, false);
        var missed = runner.scheduled(CLUB, true, job, at("2026-10-05T22:30:00Z")).orElseThrow();
        assertThat(missed.status()).isEqualTo(JobStatus.SKIPPED);
        assertThat(missed.skipReason()).isEqualTo(SkipReason.MISSED_WINDOW);
        assertThat(missed.trigger()).isEqualTo(JobTrigger.CATCH_UP);
        assertThat(events("JobFailed")).singleElement().satisfies(event -> assertThat(((Document) event.get("payload"))).containsEntry("status", "SKIPPED"));
        assertThat(job.applied()).isZero();
        var caughtUp = runner.scheduled(CLUB, true, job, at("2026-10-06T21:00:00Z")).orElseThrow();
        assertThat(caughtUp.trigger()).isEqualTo(JobTrigger.CATCH_UP);
        assertThat(caughtUp.status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    /**
     * E5-T09 (E5-T01 review #5): the baseline of a club or process with no run history is its latest occurrence ≤ now,
     * judged by R-15-05 like any other tick: older occurrences are never enumerated, so a first tick produces at most one row.
     */
    @Test void T_15_05_aClubWithNoRunHistoryStartsFromItsLatestOccurrenceOnly() {
        assertThat(runs()).isEmpty();
        // Unlimited window, three days after the club's first occurrence: one CATCH_UP for today's 06:00, nothing for the days before.
        job.configure(DAILY, 1, 0, false);
        var first = runner.scheduled(CLUB, true, job, at("2026-10-08T10:00:00Z")).orElseThrow();
        assertThat(first.trigger()).isEqualTo(JobTrigger.CATCH_UP);
        assertThat(first.scheduledFor()).isEqualTo(at("2026-10-08T04:00:00Z"));
        assertThat(first.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(runs()).hasSize(1);
        assertThat(runner.scheduled(CLUB, true, job, at("2026-10-08T10:01:00Z"))).isEmpty();
        // On time: SCHEDULE; before today's occurrence: yesterday's (the latest ≤ now) is the baseline.
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "job_runs");
        assertThat(runner.scheduled(CLUB, true, job, at("2026-10-09T04:01:00Z")).orElseThrow().trigger()).isEqualTo(JobTrigger.SCHEDULE);
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "job_runs");
        assertThat(runner.scheduled(CLUB, true, job, at("2026-10-10T03:00:00Z")).orElseThrow().scheduledFor()).isEqualTo(at("2026-10-09T04:00:00Z"));
        // A continuous process starts at the current minute.
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "job_runs");
        job.configure(CONTINUOUS, 0, 0, false);
        var continuous = runner.scheduled(CLUB, true, job, at("2026-10-10T03:00:30Z")).orElseThrow();
        assertThat(continuous.scheduledFor()).isEqualTo(at("2026-10-10T03:00:00Z"));
        assertThat(continuous.trigger()).isEqualTo(JobTrigger.SCHEDULE);
        assertThat(runs()).hasSize(1);
        // Outside the window the baseline is SKIPPED{MISSED_WINDOW} + JobFailed (R-15-05/R-15-10 as written): see T_15_05_missedWindowSkipsAndAlerts.
    }

    @Test void T_15_31_aRunWhoseLeaseExpiredIsFailedAndTakenAgainAsCatchUp() {
        job.configure(DAILY, 1, 0, false);
        var dead = new JobRun("dead-run", CLUB, JobName.TEST_NOOP, at("2026-10-05T04:00:00Z"), "2026-10-05T06:00", "Europe/Madrid", JobTrigger.SCHEDULE,
                false, JobStatus.RUNNING, null, at("2026-10-05T04:00:00Z"), null, null, List.of(), List.of(), List.of(), null, List.of(), true, "dead-holder", false);
        mongo.insert(dead);
        assertThat(locks.acquire(CLUB + ":TEST_NOOP", "dead-holder", at("2026-10-05T04:00:00Z"), Duration.ofSeconds(300))).isTrue();
        // While the lease lives the occurrence is taken and the run is left alone.
        clock.setInstant(at("2026-10-05T04:04:00Z"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant())).isEmpty();
        clock.setInstant(at("2026-10-05T04:06:00Z"));
        var retaken = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(retaken.trigger()).isEqualTo(JobTrigger.CATCH_UP);
        assertThat(retaken.status()).isEqualTo(JobStatus.SUCCEEDED);
        var reaped = mongo.findById("dead-run", JobRun.class);
        assertThat(reaped.status()).isEqualTo(JobStatus.FAILED);
        assertThat(reaped.leaseExpired()).isTrue();
        assertThat(reaped.exclusive()).isFalse();
        assertThat(events("JobFailed")).hasSize(1);
    }

    @Test @AuditCovers(AuditAction.JOB_TRIGGERED)
    void T_15_08_manualTriggerIsAuditedAndRefusedWhileRunning() {
        job.configure(DAILY, 2, 0, false);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(ADMIN, null, "ROLE_ADMIN"));
        try (var scope = TenantContext.open(CLUB)) {
            var view = triggers.trigger(JobName.TEST_NOOP, true);
            assertThat(view.job()).isEqualTo("TEST_NOOP");
            assertThat(view.effects().items()).hasSize(2);
            assertThat(view.parametersSnapshot()).containsKey("jobs.dailyTime");
            var audit = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("JOB_TRIGGERED")), Document.class, "audit_entries");
            assertThat(audit.getString("entityType")).isEqualTo("JobRun");
            assertThat(audit.getString("entityId")).isEqualTo(view.runId());
            assertThat(audit.getString("actorAccountId")).isEqualTo(ADMIN);
            assertThat(audit.get("changes").toString()).contains("job", "TEST_NOOP", "dryRun", "status", "SUCCEEDED", "counters", "WOULD_NOOP");
            assertThat(locks.acquire(CLUB + ":TEST_NOOP", "other-instance", clock.instant(), Duration.ofSeconds(300))).isTrue();
            assertThatThrownBy(() -> triggers.trigger(JobName.TEST_NOOP, false))
                    .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.JOB_ALREADY_RUNNING));
            assertThatThrownBy(() -> triggers.trigger(JobName.BILLING_REMINDER, false))
                    .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.JOB_UNKNOWN));
        }
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("JOB_TRIGGERED")), "audit_entries")).isEqualTo(1);
    }

    @Test void T_15_01_tickRunsEveryClubInCatalogOrderOncePerMinute() {
        job.configure(DAILY, 1, 0, false);
        double overruns = metrics.counter("jobs.tick.overrun").count();
        assertThat(tick.run(at("2026-10-05T04:00:20Z"))).isTrue();
        // Another tick of the same minute (a second instance) skips: normal contention, not an overrun (E5-T09).
        assertThat(tick.run(at("2026-10-05T04:00:40Z"))).isFalse();
        assertThat(metrics.counter("jobs.tick.overrun").count()).isEqualTo(overruns);
        assertThat(runs()).singleElement().satisfies(run -> assertThat(run.getString("status")).isEqualTo("SUCCEEDED"));
        var suspended = mongo.find(Query.query(Criteria.where("clubId").is(SUSPENDED)), Document.class, "job_runs");
        assertThat(suspended).isNotEmpty().allSatisfy(run -> assertThat(run.getString("skipReason")).isEqualTo("CLUB_INACTIVE"));
        // E5-T05: the five E5 processes in R-15-01 order, then the test job; P3, P4, P5, P8 and P10 have no bean yet.
        assertThat(runner.registered()).extracting(Job::name).containsExactly(JobName.WEEK_OPENING, JobName.RISK_REVIEW, JobName.WAITLIST_FIFO,
                JobName.PAYMENT_TIMEOUTS, JobName.CLEANUP, JobName.TEST_NOOP);
        assertThat(runner.registered(JobName.BILLING_REMINDER)).isEmpty();
        // A failing club does not stop the tick.
        job.configure(DAILY, 1, 0, true);
        clock.setInstant(at("2026-10-06T04:00:00Z"));
        assertThat(tick.run(clock.instant())).isTrue();
        assertThat(runs()).last().satisfies(run -> assertThat(run.getString("status")).isEqualTo("FAILED"));
    }

    /** E5-T09 (E5-T01 review #4): `jobs.tick.overrun` counts the minute ticks a slow tick made the scheduler skip. */
    @Test void T_15_01_aTickThatLastsPastTheNextMinuteIsAnOverrunAndSettlesItsLease() {
        double overruns = metrics.counter("jobs.tick.overrun").count();
        // A 40 s tick is no overrun; its lease still ends at second 55, so a tick of the same minute is refused and the next one runs.
        job.configure(DAILY, 1, 0, false);
        job.duringNextApply(() -> clock.setInstant(at("2026-10-05T04:00:40Z")));
        assertThat(tick.run(at("2026-10-05T04:00:00Z"))).isTrue();
        assertThat(metrics.counter("jobs.tick.overrun").count()).isEqualTo(overruns);
        assertThat(mongo.findById("tick", Document.class, "job_locks").getDate("expiresAt").toInstant()).isEqualTo(at("2026-10-05T04:00:55Z"));
        assertThat(tick.run(at("2026-10-05T04:00:50Z"))).isFalse();
        // A tick that lasts 2 min 10 s made the scheduler skip two minute ticks, and gives the lease back when it ends.
        clock.setInstant(at("2026-10-06T04:00:00Z"));
        job.duringNextApply(() -> clock.setInstant(at("2026-10-06T04:02:10Z")));
        assertThat(tick.run(at("2026-10-06T04:00:00Z"))).isTrue();
        assertThat(metrics.counter("jobs.tick.overrun").count()).isEqualTo(overruns + 2);
        var lease = mongo.findById("tick", Document.class, "job_locks");
        assertThat(lease.getDate("expiresAt").toInstant()).isEqualTo(at("2026-10-06T04:02:10Z"));
        assertThat(tick.run(at("2026-10-06T04:03:00Z"))).isTrue();
        // A renewed lease keeps a second tick out while the first is still running past second 30.
        assertThat(locks.acquire("tick", "slow-instance", at("2026-10-07T04:00:00Z"), Duration.ofSeconds(55))).isTrue();
        assertThat(locks.renew("tick", "slow-instance", at("2026-10-07T04:00:45Z"), Duration.ofSeconds(55))).isTrue();
        assertThat(tick.run(at("2026-10-07T04:01:00Z"))).isFalse();
        assertThat(metrics.counter("jobs.tick.overrun").count()).isEqualTo(overruns + 2);
    }
}
