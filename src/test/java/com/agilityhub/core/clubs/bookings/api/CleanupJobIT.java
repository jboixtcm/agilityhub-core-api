package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.common.application.CleanupJob;
import com.agilityhub.core.clubs.followup.application.AttachmentStorage;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.shared.domain.ApiException;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.*;

/** S15 R-15-19 P9 `cleanup` (T-15-27) at Tuesday 06-10-2026 06:00 Madrid (`jobs.dailyTime`, 04:00Z). */
class CleanupJobIT extends BookingFixtures {
    static final Instant RUN = Instant.parse("2026-10-06T04:00:00Z");
    static final List<String> TECHNICAL = List.of("attachment_uploads", "dog_documents", "export_jobs", "magic_link_tokens", "job_runs");
    @Autowired JobRunner runner;
    @Autowired CleanupJob job;
    @Autowired AttachmentStorage storage;

    @BeforeEach void technical() throws Exception {
        for (String collection : TECHNICAL) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        mongo.remove(new Query(), "job_locks");
        clock.setInstant(RUN);
        // Signup uploads: three orphans of 50 h, one referenced by a DogDocument, one orphan of 10 h, one of another club.
        upload("orphan-1", 50); upload("orphan-2", 50); upload("orphan-3", 50); upload("referenced", 50); upload("recent", 10);
        mongo.insert(new Document("_id", "signup/" + OTHER + "/202610/x/other.pdf").append("clubId", OTHER).append("purpose", "SIGNUP_DOCUMENT")
                .append("createdAt", Date.from(RUN.minus(Duration.ofHours(50)))), "attachment_uploads");
        mongo.insert(new Document("_id", "s08-doc").append("clubId", CLUB).append("dogId", "s08-d-duna").append("type", "VACCINATION_CARD").append("state", "PENDING")
                .append("files", List.of(new Document("fileKey", key("referenced")).append("name", "card.pdf"))), "dog_documents");
        // Outbox: a processed event of 91 days (deleted) and an unprocessed one of 200 days (kept).
        mongo.insert(new Document("_id", "s08-event-old").append("clubId", CLUB).append("type", "BookingCreated").append("status", "PUBLISHED")
                .append("occurredAt", Date.from(RUN.minus(Duration.ofDays(91)))).append("publishedAt", Date.from(RUN.minus(Duration.ofDays(91)))), "domain_events");
        mongo.insert(new Document("_id", "s08-event-stuck").append("clubId", CLUB).append("type", "BookingCreated").append("status", "PENDING")
                .append("occurredAt", Date.from(RUN.minus(Duration.ofDays(200)))), "domain_events");
        mongo.insert(new Document("_id", "s08-event-recent").append("clubId", CLUB).append("type", "BookingCreated").append("status", "PUBLISHED")
                .append("occurredAt", Date.from(RUN.minus(Duration.ofDays(10)))).append("publishedAt", Date.from(RUN.minus(Duration.ofDays(10)))), "domain_events");
        // Runs: eight risk-review runs of 100 days (the last five survive) and two week-opening ones (both survive).
        for (int i = 0; i < 8; i++) { run("s08-rr-" + i, JobName.RISK_REVIEW, RUN.minus(Duration.ofDays(108 - i))); }
        for (int i = 0; i < 2; i++) { run("s08-wo-" + i, JobName.WEEK_OPENING, RUN.minus(Duration.ofDays(120 - i))); }
        // Exports: a READY one of 8 days (purged) and one of 2 days (kept).
        export("s08-export-old", 8); export("s08-export-new", 2);
        // TTL documents Mongo has not removed yet (the test container runs without the TTL monitor).
        mongo.insert(new Document("_id", "s08-hold").append("clubId", CLUB).append("expiresAt", Date.from(RUN.minusSeconds(30))), "seat_holds");
        mongo.insert(new Document("_id", "s08-token").append("clubId", CLUB).append("tokenHash", "s08-hash-" + UUID.randomUUID()).append("expiresAt", Date.from(RUN.minusSeconds(30))), "magic_link_tokens");
        mongo.insert(new Document("_id", "s08-idem").append("clubId", CLUB).append("createdAt", Date.from(RUN.minus(Duration.ofHours(25)))), "idempotency_records");
        mongo.insert(new Document("_id", CLUB + ":REMINDERS").append("holder", "gone").append("expiresAt", Date.from(RUN.minusSeconds(30))), "job_locks");
    }
    /** Unique per test run: the local attachment store keeps its files between runs. */
    private final String batch = UUID.randomUUID().toString();
    String key(String name) { return "signup/" + CLUB + "/202610/" + batch + "-" + name + "/card.pdf"; }
    private void upload(String name, int hoursAgo) throws Exception {
        mongo.insert(new Document("_id", key(name)).append("clubId", CLUB).append("purpose", "SIGNUP_DOCUMENT").append("fileName", "card.pdf")
                .append("mimeType", "application/pdf").append("sizeBytes", 3L).append("createdAt", Date.from(RUN.minus(Duration.ofHours(hoursAgo))))
                .append("expiresAt", Date.from(RUN.minus(Duration.ofHours(hoursAgo)).plusSeconds(900))), "attachment_uploads");
        ((com.agilityhub.core.clubs.followup.persistence.LocalAttachmentStorage) storage).put(key(name), "application/pdf", 3, new ByteArrayInputStream(new byte[] {1, 2, 3}));
    }
    private void run(String id, JobName name, Instant at) { run(id, CLUB, name, at, false, JobStatus.SUCCEEDED, null); }
    private void run(String id, String club, JobName name, Instant at, boolean dryRun, JobStatus status, SkipReason skip) {
        mongo.insert(new JobRun(id, club, name, at, "x", "Europe/Madrid", dryRun ? JobTrigger.MANUAL : JobTrigger.SCHEDULE, dryRun, status, skip, at, at, 0L,
                List.of(), List.of(), List.of(), null, List.of(), true, null, false));
    }
    private void export(String id, int daysAgo) {
        mongo.insert(new Document("_id", id).append("clubId", CLUB).append("ownerAccountId", "s08-admin").append("kind", "LIST").append("status", "READY")
                .append("createdAt", Date.from(RUN.minus(Duration.ofDays(daysAgo)))).append("fileKey", id + ".xlsx").append("fileKeys", List.of(id + ".xlsx"))
                .append("expiresAt", Date.from(RUN.plus(Duration.ofDays(1)))).append("attempts", 1), "export_jobs");
    }
    private boolean stored(String name) {
        try { storage.metadata(key(name)); return true; } catch (ApiException missing) { return false; }
    }
    private Map<String, Long> counters(JobRun run) {
        var map = new TreeMap<String, Long>(); run.counters().forEach(e -> map.put(e.key(), ((Number) e.value()).longValue())); return map;
    }
    private long count(String collection) { return mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), collection); }

    @Test void T_15_27_cleanupDeletesOnlyExpiredTechnicalDataAndKeepsTheLastFiveRuns() {
        var before = new TreeMap<String, Long>();
        for (String c : List.of("attachment_uploads", "domain_events", "export_jobs", "seat_holds", "magic_link_tokens", "idempotency_records")) { before.put(c, count(c)); }
        long runs = count("job_runs");
        var dry = runner.manual(CLUB, JobName.CLEANUP, true, "s08-admin");
        assertThat(counters(dry)).containsEntry("WOULD_DELETE_orphanUploads", 3L).containsEntry("WOULD_DELETE_exports", 1L)
                .containsEntry("WOULD_DELETE_domainEvents", 1L).containsEntry("WOULD_DELETE_jobRuns", 3L).containsEntry("WOULD_DELETE_stripeEvents", 0L)
                .containsEntry("WOULD_DELETE", 6L).containsEntry("ttlPendingSeatHolds", 1L).containsEntry("ttlPendingMagicLinkTokens", 1L)
                .containsEntry("ttlPendingIdempotencyRecords", 1L).containsEntry("ttlPendingJobLocks", 1L).doesNotContainKey("orphanUploadsDeleted");
        // The dry run writes nothing but its JobRun.
        before.forEach((collection, value) -> assertThat(count(collection)).as(collection).isEqualTo(value));
        assertThat(count("job_runs")).isEqualTo(runs + 1);
        assertThat(stored("orphan-1")).isTrue();

        var real = runner.scheduled(CLUB, true, job, RUN).orElseThrow();
        assertThat(real.trigger()).isEqualTo(JobTrigger.SCHEDULE);
        assertThat(real.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(real.items()).extracting(JobRun.Item::entityId).containsExactlyElementsOf(dry.items().stream().map(JobRun.Item::entityId).toList());
        assertThat(real.items()).extracting(JobRun.Item::action).containsOnly("DELETE");
        assertThat(counters(real)).containsEntry("orphanUploadsDeleted", 3L).containsEntry("exportsPurged", 1L).containsEntry("domainEventsDeleted", 1L)
                .containsEntry("jobRunsDeleted", 3L).containsEntry("stripeEventsDeleted", 0L).containsEntry("ttlPendingSeatHolds", 1L);
        assertThat(real.parametersSnapshot()).contains(new JobRun.Entry("jobs.retention.orphanUploadsHours", 48), new JobRun.Entry("jobs.retention.jobRunsDays", 90));
        // Uploads: the three old orphans are gone (grant and file); the referenced and the recent ones stay; another club is untouched.
        assertThat(List.of("orphan-1", "orphan-2", "orphan-3")).noneMatch(this::stored);
        assertThat(stored("referenced")).isTrue(); assertThat(stored("recent")).isTrue();
        assertThat(mongo.findById(key("referenced"), Document.class, "attachment_uploads")).isNotNull();
        assertThat(mongo.findById(key("orphan-1"), Document.class, "attachment_uploads")).isNull();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(OTHER)), "attachment_uploads")).isEqualTo(1);
        // Outbox: processed of 91 days deleted, unprocessed of 200 days and recent ones kept.
        assertThat(mongo.findById("s08-event-old", Document.class, "domain_events")).isNull();
        assertThat(mongo.findById("s08-event-stuck", Document.class, "domain_events")).isNotNull();
        assertThat(mongo.findById("s08-event-recent", Document.class, "domain_events")).isNotNull();
        // Runs: the three oldest risk-review runs go, the last five and both week-opening runs stay.
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("job").is("RISK_REVIEW")), Document.class, "job_runs"))
                .extracting(d -> d.getString("_id")).containsExactlyInAnyOrder("s08-rr-3", "s08-rr-4", "s08-rr-5", "s08-rr-6", "s08-rr-7");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("job").is("WEEK_OPENING")), "job_runs")).isEqualTo(2);
        // Exports: the old one is purged (tombstone EXPIRED with its files unset and an immediate purgeAt), the recent one kept.
        var purged = mongo.findById("s08-export-old", Document.class, "export_jobs");
        assertThat(purged.getString("status")).isEqualTo("EXPIRED");
        assertThat(purged.get("fileKey")).isNull();
        assertThat(purged.getDate("purgeAt").toInstant()).isEqualTo(RUN);
        assertThat(mongo.findById("s08-export-new", Document.class, "export_jobs").getString("status")).isEqualTo("READY");
        // TTL-managed documents are only reported, never deleted.
        assertThat(count("seat_holds")).isEqualTo(1); assertThat(count("magic_link_tokens")).isEqualTo(1);
        assertThat(mongo.findById(CLUB + ":REMINDERS", Document.class, "job_locks")).isNotNull();
        // No business event: only the SchedulerRun of the run itself.
        assertThat(eventsOf("SchedulerRun")).hasSize(1);
        // A second run finds nothing left.
        var again = runner.manual(CLUB, JobName.CLEANUP, false, "s08-admin");
        assertThat(again.items()).isEmpty();
        System.out.println("E5-T05 cleanup JobRun dry  " + mongo.findById(dry.id(), Document.class, "job_runs").toJson());
        System.out.println("E5-T05 cleanup JobRun real " + mongo.findById(real.id(), Document.class, "job_runs").toJson());
    }

    @Autowired com.agilityhub.core.clubs.common.persistence.CleanupRepository cleanup;

    @Test void T_15_27_cleanupIsBoundToItsClubAndKeepsTheLastFiveExecutionsNotDryRunsOrSkips() {
        // Newer than every real risk-review run: five dry runs and two SKIPPED rows of yesterday, plus an old dry run of 100 days.
        for (int i = 0; i < 5; i++) { run("s08-rr-dry-" + i, CLUB, JobName.RISK_REVIEW, RUN.minus(Duration.ofDays(1)).plusSeconds(i), true, JobStatus.SUCCEEDED, null); }
        for (int i = 0; i < 2; i++) { run("s08-rr-skip-" + i, CLUB, JobName.RISK_REVIEW, RUN.minus(Duration.ofHours(2)).plusSeconds(i), false, JobStatus.SKIPPED, SkipReason.DISABLED); }
        run("s08-rr-dry-old", CLUB, JobName.RISK_REVIEW, RUN.minus(Duration.ofDays(100)), true, JobStatus.SUCCEEDED, null);
        // Another club with the same expired technical data: none of it may go.
        mongo.save(new Document("_id", "s08-other-event-old").append("clubId", OTHER).append("type", "BookingCreated").append("status", "PUBLISHED")
                .append("occurredAt", Date.from(RUN.minus(Duration.ofDays(91)))).append("publishedAt", Date.from(RUN.minus(Duration.ofDays(91)))), "domain_events");
        for (int i = 0; i < 8; i++) { run("s08-other-rr-" + i, OTHER, JobName.RISK_REVIEW, RUN.minus(Duration.ofDays(108 - i)), false, JobStatus.SUCCEEDED, null); }

        var real = runner.scheduled(CLUB, true, job, RUN).orElseThrow();
        // Kept: the five newest real executions (rr-3…rr-7) and the recent dry/skipped rows; deleted: rr-0…rr-2 and the old dry run.
        assertThat(counters(real)).containsEntry("jobRunsDeleted", 4L).containsEntry("domainEventsDeleted", 1L).containsEntry("orphanUploadsDeleted", 3L);
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("job").is("RISK_REVIEW").and("dryRun").is(false).and("status").is("SUCCEEDED")),
                Document.class, "job_runs")).extracting(d -> d.getString("_id")).containsExactlyInAnyOrder("s08-rr-3", "s08-rr-4", "s08-rr-5", "s08-rr-6", "s08-rr-7");
        assertThat(mongo.findById("s08-rr-dry-old", Document.class, "job_runs")).isNull();
        assertThat(mongo.count(Query.query(Criteria.where("_id").regex("^s08-rr-(dry|skip)-\\d")), "job_runs")).isEqualTo(7);
        // The other club is untouched: its old event, its eight old runs and its orphan upload.
        assertThat(mongo.findById("s08-other-event-old", Document.class, "domain_events")).isNotNull();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(OTHER).and("job").is("RISK_REVIEW")), "job_runs")).isEqualTo(8);
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(OTHER)), "attachment_uploads")).isEqualTo(1);
        // The repository is bound to the open tenant like TenantRepository: another club → TENANT_MISMATCH, no tenant → refused.
        try (var tenant = com.agilityhub.core.shared.application.TenantContext.open(CLUB)) {
            assertThat(cleanup.processedEvents(CLUB, RUN.minus(Duration.ofDays(90)))).isZero();
            for (Runnable call : List.<Runnable>of(() -> cleanup.processedEvents(OTHER, RUN), () -> cleanup.deleteProcessedEvents(OTHER, RUN),
                    () -> cleanup.expiredRuns(OTHER, "RISK_REVIEW", RUN, 5), () -> cleanup.deleteRuns(OTHER, List.of("s08-other-rr-0")),
                    () -> cleanup.orphanUploads(OTHER, RUN), () -> cleanup.deleteUpload(OTHER, "signup/" + OTHER + "/202610/x/other.pdf"),
                    () -> cleanup.ttlPending(OTHER, "job_locks", "expiresAt", RUN), () -> cleanup.ttlPending(OTHER, "seat_holds", "expiresAt", RUN),
                    () -> cleanup.stripeEvents(OTHER, RUN))) {
                assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.code()).isEqualTo(com.agilityhub.core.shared.domain.ErrorCode.TENANT_MISMATCH));
            }
        }
        assertThatThrownBy(() -> cleanup.processedEvents(CLUB, RUN)).isInstanceOf(RuntimeException.class);
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(OTHER).and("job").is("RISK_REVIEW")), "job_runs")).isEqualTo(8);
    }
}
