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
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB)).with(org.springframework.data.domain.Sort.by("startedAt", "_id")), Document.class, "job_runs");
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
        // A retake that loses the race on the unique index writes nothing and releases its lease.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(first.id())), new org.springframework.data.mongodb.core.query.Update().set("leaseExpired", true), JobRun.class);
        mongo.remove(Query.query(Criteria.where("scheduledFor").is(at("2026-10-06T04:00:00Z"))), JobRun.class);
        clock.setInstant(at("2026-10-05T04:01:00Z"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant())).isEmpty();
        assertThat(mongo.findById(CLUB + ":TEST_NOOP", Document.class, "job_locks")).isNull();
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
        var dry = runner.manual(CLUB, JobName.TEST_NOOP, true, ADMIN);
        var after = counts();
        assertThat(after.get("job_runs")).isEqualTo(before.getOrDefault("job_runs", 0L) + 1);
        after.remove("job_runs"); before.remove("job_runs"); before.remove("job_locks"); after.remove("job_locks");
        assertThat(after).isEqualTo(before);
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
        // Evidence of E5-T01: the stored documents of one dry and one real run of the test job.
        System.out.println("E5-T01 JobRun dry  " + mongo.findById(dry.id(), Document.class, "job_runs").toJson());
        System.out.println("E5-T01 JobRun real " + mongo.findById(real.id(), Document.class, "job_runs").toJson());
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
            assertThatThrownBy(() -> triggers.trigger(JobName.RISK_REVIEW, false))
                    .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.JOB_UNKNOWN));
        }
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("JOB_TRIGGERED")), "audit_entries")).isEqualTo(1);
    }

    @Test void T_15_01_tickRunsEveryClubInCatalogOrderOncePerMinute() {
        job.configure(DAILY, 1, 0, false);
        double overruns = metrics.counter("jobs.tick.overrun").count();
        assertThat(tick.run(at("2026-10-05T04:00:20Z"))).isTrue();
        assertThat(tick.run(at("2026-10-05T04:00:40Z"))).isFalse();
        assertThat(metrics.counter("jobs.tick.overrun").count()).isEqualTo(overruns + 1);
        assertThat(runs()).singleElement().satisfies(run -> assertThat(run.getString("status")).isEqualTo("SUCCEEDED"));
        var suspended = mongo.find(Query.query(Criteria.where("clubId").is(SUSPENDED)), Document.class, "job_runs");
        assertThat(suspended).singleElement().satisfies(run -> assertThat(run.getString("skipReason")).isEqualTo("CLUB_INACTIVE"));
        assertThat(runner.registered()).extracting(Job::name).containsExactly(JobName.TEST_NOOP);
        assertThat(runner.registered(JobName.RISK_REVIEW)).isEmpty();
        // A failing club does not stop the tick.
        job.configure(DAILY, 1, 0, true);
        clock.setInstant(at("2026-10-06T04:00:00Z"));
        assertThat(tick.run(clock.instant())).isTrue();
        assertThat(runs()).last().satisfies(run -> assertThat(run.getString("status")).isEqualTo("FAILED"));
    }
}
