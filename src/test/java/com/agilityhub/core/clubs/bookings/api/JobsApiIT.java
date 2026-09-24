package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.platform.persistence.jobs.JobLockRepository;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** S15 §6 / R-15-09 club and platform routes over the real E5 processes (T-15-08, T-15-09, T-15-10 overview half). */
class JobsApiIT extends BookingFixtures {
    @Autowired JobLockRepository locks;
    static final RequestPostProcessor PLATFORM = jwt().jwt(j -> j.subject("s08-platform")).authorities(() -> "ROLE_AGILITYHUB_ADMIN");

    @BeforeEach void runs() {
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "job_runs");
        mongo.remove(new Query(), "job_locks");
    }
    private JsonNode platform(MockHttpServletRequestBuilder request, int expected) throws Exception {
        var response = mvc.perform(request.with(PLATFORM)).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        return mapper.readTree(response.getContentAsString());
    }
    private void run(String id, String club, JobName name, JobStatus status, Instant at) {
        mongo.insert(new JobRun(id, club, name, at, "x", "Europe/Madrid", JobTrigger.SCHEDULE, false, status, null, at, at, 0L,
                List.of(new JobRun.Entry("reviewed", 1L)), List.of(), List.of(), null, List.of(), false, null, false));
    }

    @Test @AuditCovers(AuditAction.JOB_TRIGGERED)
    void T_15_08_manualTriggerRunsTheProcessEvenSwitchedOffIsAuditedAndGuarded() throws Exception {
        var run = call(POST, "/jobs/risk-review/trigger", Map.of("dryRun", false), as("admin"), 200);
        assertThat(run.path("job").asText()).isEqualTo("RISK_REVIEW");
        assertThat(run.path("trigger").asText()).isEqualTo("MANUAL");
        assertThat(run.path("dryRun").asBoolean()).isFalse();
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("actorAccountId").asText()).isEqualTo("s08-admin");
        assertThat(run.path("scheduledFor").asText()).isEqualTo("2026-10-06T08:00:00Z");
        assertThat(run.at("/parametersSnapshot/classes.minDogs").asInt()).isEqualTo(2);
        assertThat(eventsOf("SchedulerRun")).singleElement().satisfies(e -> assertThat(e.getString("origin")).isEqualTo("BACKOFFICE"));
        var audit = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("JOB_TRIGGERED")), Document.class, "audit_entries");
        assertThat(audit.getString("entityType")).isEqualTo("JobRun");
        assertThat(audit.getString("entityId")).isEqualTo(run.path("runId").asText());
        // The switch stops the calendar, not the admin (principle 0.3.7).
        call(PUT, "/jobs/risk-review/switch", Map.of("enabled", false), as("admin"), 200);
        assertThat(call(POST, "/jobs/risk-review/trigger", Map.of("dryRun", true), as("admin"), 200).path("status").asText()).isEqualTo("SUCCEEDED");
        // A run in progress (the lease is held) → 409 JOB_ALREADY_RUNNING.
        assertThat(locks.acquire(CLUB + ":RISK_REVIEW", "other-instance", clock.instant(), Duration.ofSeconds(300))).isTrue();
        assertThat(code(call(POST, "/jobs/risk-review/trigger", Map.of("dryRun", false), as("admin"), 409))).isEqualTo("JOB_ALREADY_RUNNING");
        locks.release(CLUB + ":RISK_REVIEW", "other-instance");
        // Guards: impersonation, instructor, member, unknown process, module off.
        call(POST, "/jobs/risk-review/trigger", Map.of("dryRun", false), impersonating("admin", "s08-m-laura"), 403);
        call(POST, "/jobs/risk-review/trigger", Map.of("dryRun", false), as("inst"), 403);
        call(POST, "/jobs/risk-review/trigger", Map.of("dryRun", false), as("laura"), 403);
        assertThat(code(call(POST, "/jobs/foo/trigger", Map.of("dryRun", false), as("admin"), 404))).isEqualTo("JOB_UNKNOWN");
        assertThat(code(call(POST, "/jobs/risk-review/trigger", Map.of(), as("admin"), 400))).isEqualTo("VALIDATION_ERROR");
        modules(Module.WAITLIST, Module.PACKS, Module.FREE_TRAINING);
        assertThat(code(call(POST, "/jobs/payment-timeouts/trigger", Map.of("dryRun", false), as("admin"), 404))).isEqualTo("MODULE_DISABLED");
        // The platform console runs the same code in the addressed club.
        var console = platform(post("/api/v1/platform/clubs/" + CLUB + "/jobs/cleanup/trigger").contentType("application/json").content("{\"dryRun\":true}"), 200);
        assertThat(console.path("job").asText()).isEqualTo("CLEANUP");
        assertThat(console.path("dryRun").asBoolean()).isTrue();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("JOB_TRIGGERED")), "audit_entries")).isEqualTo(3);
        platform(post("/api/v1/platform/clubs/s08-missing/jobs/cleanup/trigger").contentType("application/json").content("{\"dryRun\":true}"), 404);
    }

    @Test void T_15_09_theSwitchWritesTheParameterAndTheListsAreScopedToTheTenant() throws Exception {
        var switched = call(PUT, "/jobs/reminders/switch", Map.of("enabled", false), as("admin"), 200);
        assertThat(switched.path("name").asText()).isEqualTo("reminders");
        assertThat(switched.path("enabled").asBoolean()).isFalse();
        assertThat(mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("key").is("jobs.reminders.enabled")), Document.class, "parameters").getBoolean("value")).isFalse();
        assertThat(eventsOf("ParameterChanged")).isNotEmpty();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("PARAMETER_CHANGED")), "audit_entries")).isEqualTo(1);
        call(PUT, "/jobs/risk-review/switch", Map.of("enabled", false), as("admin"), 200);
        // GET /jobs: implemented processes whose module is on (waitlist-fifo needs FIFO; P3, P4, P5, P8, P10 have no implementation yet).
        call(POST, "/jobs/risk-review/trigger", Map.of("dryRun", false), as("admin"), 200);
        var jobs = call(GET, "/jobs", null, as("admin"), 200).path("items");
        assertThat(jobs).extracting(j -> j.path("name").asText()).containsExactly("week-opening", "risk-review", "payment-timeouts", "cleanup");
        var review = jobs.get(1);
        assertThat(review.path("jobName").asText()).isEqualTo("RISK_REVIEW");
        assertThat(review.path("enabled").asBoolean()).isFalse();
        assertThat(review.at("/schedule/kind").asText()).isEqualTo("DAILY");
        assertThat(review.at("/schedule/localTime").asText()).isEqualTo("07:30");
        assertThat(review.path("nextScheduledForLocal").asText()).isEqualTo("2026-10-07T07:30");
        assertThat(review.at("/lastRun/trigger").asText()).isEqualTo("MANUAL");
        assertThat(review.at("/lastRun/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(jobs.get(0).at("/schedule/dayOfWeek").asText()).isEqualTo("SUNDAY");
        assertThat(jobs.get(0).path("nextScheduledForLocal").asText()).isEqualTo("2026-10-11T20:00");
        assertThat(jobs.get(2).at("/schedule/kind").asText()).isEqualTo("CONTINUOUS");
        assertThat(jobs.get(2).path("module").asText()).isEqualTo("SINGLE_CLASS");
        parameter("waitlist.mode", "FIFO");
        assertThat(call(GET, "/jobs", null, as("admin"), 200).path("items")).extracting(j -> j.path("name").asText()).contains("waitlist-fifo");
        // Runs: universal list of the tenant only, with filters; the run sheet; another club's run is 404.
        run("s08-other-run", OTHER, JobName.RISK_REVIEW, JobStatus.SUCCEEDED, clock.instant());
        run("s08-failed-run", CLUB, JobName.RISK_REVIEW, JobStatus.FAILED, clock.instant().minus(Duration.ofDays(1)));
        var list = call(GET, "/jobs/risk-review/runs", null, as("admin"), 200);
        assertThat(list.path("items")).extracting(r -> r.path("runId").asText()).doesNotContain("s08-other-run").contains("s08-failed-run").hasSize(2);
        assertThat(list.path("totalItems").asLong()).isEqualTo(2);
        assertThat(call(GET, "/jobs/risk-review/runs?filter=status:eq:FAILED", null, as("admin"), 200).path("items"))
                .singleElement().satisfies(r -> assertThat(r.path("counters").path("reviewed").asLong()).isEqualTo(1));
        assertThat(call(GET, "/jobs/risk-review/runs?sort=startedAt,asc", null, as("admin"), 200).path("items").get(0).path("runId").asText()).isEqualTo("s08-failed-run");
        assertThat(code(call(GET, "/jobs/risk-review/runs?filter=foo:eq:1", null, as("admin"), 400))).isEqualTo("INVALID_FILTER");
        String runId = list.path("items").get(0).path("runId").asText();
        var sheet = call(GET, "/jobs/risk-review/runs/" + runId, null, as("admin"), 200);
        assertThat(sheet.path("runId").asText()).isEqualTo(runId);
        assertThat(sheet.has("parametersSnapshot")).isTrue();
        assertThat(sheet.path("errors").isArray()).isTrue();
        call(GET, "/jobs/risk-review/runs/s08-other-run", null, as("admin"), 404);
        call(GET, "/jobs/cleanup/runs/" + runId, null, as("admin"), 404);
        call(GET, "/jobs", null, as("inst"), 403);
        call(GET, "/jobs", null, impersonating("admin", "s08-m-laura"), 403);
    }

    /** No platform pass counter or item in a club-scoped view of a run (AGENTS rule 4). */
    private static void assertClubOnly(JsonNode counters, JsonNode items) {
        assertThat(counters.isObject()).isTrue();
        assertThat(counters.fieldNames()).toIterable().noneMatch(key -> key.toLowerCase(Locale.ROOT).contains("platform"));
        if (items != null) { assertThat(items).noneMatch(item -> item.path("entityType").asText().startsWith("Platform")); }
    }

    /** E5-T13 (review E5-T10 #5, AGENTS rule 4): the P9 platform pass stays out of the club's views, not out of the stored run. */
    @Test void T_15_27_thePlatformPassStaysOutOfTheRunViewsOfTheClubThatClaimedIt() throws Exception {
        var old = Date.from(clock.instant().minus(Duration.ofDays(91)));
        mongo.save(new Document("_id", "s08-platform-event-old").append("type", "AccountCreated").append("status", "PUBLISHED")
                .append("occurredAt", old).append("publishedAt", old), "domain_events");
        // Dry runs: the club's shows no platform counter or item, and its plan counter counts only the items it shows; the console's shows them.
        var dry = call(POST, "/jobs/cleanup/trigger", Map.of("dryRun", true), as("admin"), 200);
        assertClubOnly(dry.at("/effects/counters"), dry.at("/effects/items"));
        assertThat(dry.at("/effects/counters/WOULD_DELETE").asLong(0)).isEqualTo(dry.at("/effects/items").size());
        var console = platform(post("/api/v1/platform/clubs/" + CLUB + "/jobs/cleanup/trigger").contentType("application/json").content("{\"dryRun\":true}"), 200);
        assertThat(console.at("/effects/counters/platformPass").asLong()).isEqualTo(1);
        assertThat(console.at("/effects/counters/WOULD_DELETE_platformDomainEvents").asLong()).isPositive();
        assertThat(console.at("/effects/items")).anyMatch(item -> item.path("entityType").asText().equals("PlatformDomainEvents"));
        // A minute later the club's ADMIN runs P9 for real: this run claims the platform pass.
        clock.setInstant(clock.instant().plus(Duration.ofMinutes(1)));
        var real = call(POST, "/jobs/cleanup/trigger", Map.of("dryRun", false), as("admin"), 200);
        assertClubOnly(real.at("/effects/counters"), real.at("/effects/items"));
        String runId = real.path("runId").asText();
        var stored = mongo.findById(runId, JobRun.class);
        assertThat(stored.counters()).filteredOn(entry -> entry.key().equals("platformPass"))
                .singleElement().satisfies(entry -> assertThat(((Number) entry.value()).longValue()).isEqualTo(1L));
        assertThat(stored.items()).extracting(JobRun.Item::entityType).contains("PlatformDomainEvents");
        assertThat(mongo.findById("s08-platform-event-old", Document.class, "domain_events")).isNull();
        // D11: the run sheet, the history and the process row.
        var sheet = call(GET, "/jobs/cleanup/runs/" + runId, null, as("admin"), 200);
        assertClubOnly(sheet.at("/effects/counters"), sheet.at("/effects/items"));
        assertThat(sheet.at("/effects/counters/domainEventsDeleted").isNumber()).isTrue();
        var history = call(GET, "/jobs/cleanup/runs", null, as("admin"), 200).path("items");
        assertThat(history).hasSize(3).allSatisfy(row -> assertClubOnly(row.path("counters"), null));
        var cleanupRow = call(GET, "/jobs", null, as("admin"), 200).path("items").get(3);
        assertThat(cleanupRow.path("name").asText()).isEqualTo("cleanup");
        assertThat(cleanupRow.at("/lastRun/runId").asText()).isEqualTo(runId);
        assertClubOnly(cleanupRow.at("/lastRun/counters"), null);
        // The club's audit trail of the trigger has no platform counter either.
        var audit = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("JOB_TRIGGERED").and("entityId").is(runId)), Document.class, "audit_entries");
        assertThat(audit.get("changes").toString()).contains("domainEventsDeleted").doesNotContain("platform");
        // The platform console keeps them.
        var overview = platform(get("/api/v1/platform/jobs/overview").param("clubId", CLUB), 200);
        assertThat(overview.at("/clubs/0/jobs")).filteredOn(cell -> cell.path("name").asText().equals("cleanup"))
                .singleElement().satisfies(cell -> assertThat(cell.at("/lastRun/counters/platformPass").asLong()).isEqualTo(1));
    }

    @Test void T_15_10_theOverviewMarksHealthPerClubAndProcess() throws Exception {
        Instant now = clock.instant();
        run("s08-rr-ok", CLUB, JobName.RISK_REVIEW, JobStatus.SUCCEEDED, now.minus(Duration.ofHours(2)));
        run("s08-cl-failed", CLUB, JobName.CLEANUP, JobStatus.FAILED, now.minus(Duration.ofHours(1)));
        run("s08-pt-ok", CLUB, JobName.PAYMENT_TIMEOUTS, JobStatus.SUCCEEDED, now.minus(Duration.ofMinutes(2)));
        run("s08-pt-partial", CLUB, JobName.PAYMENT_TIMEOUTS, JobStatus.PARTIAL, now.minus(Duration.ofMinutes(1)));
        var overview = platform(get("/api/v1/platform/jobs/overview").param("clubId", CLUB), 200);
        assertThat(overview.path("clubs")).singleElement().satisfies(club -> {
            assertThat(club.path("clubId").asText()).isEqualTo(CLUB);
            assertThat(club.path("timeZone").asText()).isEqualTo("Europe/Madrid");
            var health = new TreeMap<String, String>();
            club.path("jobs").forEach(cell -> health.put(cell.path("name").asText(), cell.path("health").asText()));
            assertThat(health).containsEntry("risk-review", "OK").containsEntry("cleanup", "ALERT").containsEntry("payment-timeouts", "WARN")
                    .containsEntry("week-opening", "OK");
        });
        assertThat(platform(get("/api/v1/platform/jobs/overview").param("clubId", CLUB).param("status", "ALERT"), 200).at("/clubs/0/jobs"))
                .extracting(cell -> cell.path("name").asText()).containsExactly("cleanup");
        // Two days without a success turn a daily process red.
        clock.setInstant(now.plus(Duration.ofDays(3)));
        assertThat(platform(get("/api/v1/platform/jobs/overview").param("clubId", CLUB).param("status", "ALERT"), 200).at("/clubs/0/jobs"))
                .extracting(cell -> cell.path("name").asText()).contains("risk-review", "cleanup");
        platform(get("/api/v1/platform/jobs/overview").param("clubId", "s08-missing"), 404);
        call(GET, "/platform/jobs/overview", null, as("admin"), 403);
    }
}
