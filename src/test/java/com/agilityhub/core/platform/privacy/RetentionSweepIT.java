package com.agilityhub.core.platform.privacy;

import com.agilityhub.core.clubs.common.application.ExportStorage;
import com.agilityhub.core.platform.application.jobs.JobName;
import com.agilityhub.core.platform.application.jobs.JobRunner;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequest;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.assertj.core.api.Assertions.*;

/** S14 R-14-16 / T-14-20 (E11-T01, INC-10): the monthly `RetentionSweep` through S15, with its `JobRun` and `SchedulerRun`. */
class RetentionSweepIT extends PrivacyItFixtures {
    static final Instant RUN = Instant.parse("2026-10-01T04:00:00Z");
    @Autowired JobRunner runner; @Autowired ExportStorage exports;
    @Autowired com.agilityhub.core.payments.persistence.PaymentsErasureRepository remittanceFiles;
    final String oldXml = "remittances/" + CLUB + "/2020-06/" + java.util.UUID.randomUUID() + ".xml";

    @BeforeEach void seed() throws Exception {
        seedClubs();
        clock.setInstant(RUN);
        member(CLUB, "left-2020", 1, "11111111H", LocalDate.of(2020, 8, 31));
        member(CLUB, "left-2023", 2, "22222222J", LocalDate.of(2023, 5, 1));
        member(CLUB, "blocked-2020", 3, "33333333P", LocalDate.of(2019, 1, 1));
        member(CLUB, "rejected-old", 4, "44444444A", LocalDate.of(2025, 1, 1));
        member(CLUB, "rejected-recent", 5, "55555555K", LocalDate.of(2025, 1, 1));
        rejected("rejected-old", RUN.minus(Duration.ofDays(400)));
        rejected("rejected-recent", RUN.minus(Duration.ofDays(100)));
        member(OTHER, "foreign-2020", 1, "66666666Q", LocalDate.of(2020, 1, 1));
        raw("invoices", "_id", "invoice-failed", "clubId", CLUB, "memberId", "blocked-2020", "series", "2019", "number", 1L, "status", "FAILED");
        var file = Files.createTempFile("remittance", ".xml"); Files.writeString(file, "<Document/>");
        exports.put(oldXml, file, "application/xml"); Files.delete(file);
        raw("remittances", "_id", "remittance-2020", "clubId", CLUB, "messageId", "msg-2020", "period", "2020-07", "creationAt", Instant.parse("2020-06-20T10:00:00Z"), "fileKey", oldXml, "status", "SUBMITTED");
        raw("remittances", "_id", "remittance-2025", "clubId", CLUB, "messageId", "msg-2025", "period", "2025-07", "creationAt", Instant.parse("2025-06-20T10:00:00Z"), "fileKey", "remittances/kept.xml", "status", "SUBMITTED");
        raw("audit_entries", "_id", "audit-2019", "clubId", CLUB, "at", Instant.parse("2019-06-01T10:00:00Z"), "action", "PARAMETER_CHANGED", "entityType", "Parameter", "entityId", "x",
                "changes", java.util.List.of(), "traceId", "t", "actorRole", "ADMIN", "origin", "BACKOFFICE");
        raw("audit_entries", "_id", "audit-2022", "clubId", CLUB, "at", Instant.parse("2022-06-01T10:00:00Z"), "action", "PARAMETER_CHANGED", "entityType", "Parameter", "entityId", "x",
                "changes", java.util.List.of(), "traceId", "t", "actorRole", "ADMIN", "origin", "BACKOFFICE");
        raw("export_jobs", "_id", "expired-job", "clubId", CLUB, "ownerAccountId", "admin-account", "kind", "LIST", "listKey", "members", "format", "XLSX", "status", "READY",
                "createdAt", Instant.parse("2026-08-20T10:00:00Z"), "expiresAt", Instant.parse("2026-08-27T10:00:00Z"), "fileKey", "exports/" + CLUB + "/expired-job/x.xlsx", "attempts", 1);
    }
    void rejected(String id, Instant leftAt) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().unset("leaveDate").set("leftReason", "SIGNUP_REJECTED").set("leftAt", leftAt), "members");
    }
    static Map<String, Object> counters(JobRun run) { return run.counters().stream().collect(Collectors.toMap(JobRun.Entry::key, JobRun.Entry::value)); }
    static final String TRIGGER = "/api/v1/jobs/retention-sweep/trigger";
    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path);
    }
    /** `POST …/trigger {dryRun: false, confirmation: {dryRunId, counters}}` of PRIV-02. */
    static Map<String, Object> confirmed(String dryRunId, Object counters) {
        return Map.of("dryRun", false, "confirmation", Map.of("dryRunId", dryRunId, "counters", counters));
    }
    static void refused(com.fasterxml.jackson.databind.JsonNode body, String reason) {
        assertThat(body.path("code").asText()).isEqualTo("SIMULATION_STALE");
        assertThat(body.at("/details/reason").asText()).isEqualTo(reason);
    }
    /** Nothing of the seed was erased, purged or deleted, and no real run was written. */
    void nothingDeleted() throws Exception {
        assertThat(document("members", "left-2020").getString("firstName")).isEqualTo("Fictional");
        assertThat(document("members", "rejected-old").getString("firstName")).isEqualTo("Fictional");
        assertThat(mongo.count(new Query(), "erasure_requests")).isZero();
        assertThat(document("audit_entries", "audit-2019")).isNotNull();
        assertThat(document("remittances", "remittance-2020")).containsEntry("fileKey", oldXml);
        assertThat(exports.open(oldXml).readAllBytes()).isNotEmpty();
        assertThat(document("export_jobs", "expired-job")).containsEntry("status", "READY");
        assertThat(mongo.count(Query.query(Criteria.where("job").is("RETENTION_SWEEP").and("dryRun").is(false)), "job_runs")).isZero();
    }

    /** S15 R-15-01 rows of E11-T01: both processes are listed and can be run by hand, but a legal duty has no switch. */
    @Test void T_14_20_R_14_16_theErasureProcessesAreListedAndHaveNoSwitch() throws Exception {
        var jobs = json(admin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/jobs")), 200).path("items");
        assertThat(jobs.findValuesAsText("name")).contains("erasures", "retention-sweep");
        assertThat(jobs).filteredOn(row -> row.path("name").asText().equals("retention-sweep")).singleElement()
                .satisfies(row -> assertThat(row.path("enabled").asBoolean()).isTrue())
                .satisfies(row -> assertThat(row.at("/schedule/dayOfMonth").asInt()).isEqualTo(1));
        for (String name : java.util.List.of("erasures", "retention-sweep")) {
            var refused = json(admin(body(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/jobs/" + name + "/switch"), Map.of("enabled", false))), 404);
            assertThat(refused.path("code").asText()).isEqualTo("JOB_UNKNOWN");
            assertThat(refused.at("/details/reason").asText()).isEqualTo("NO_SWITCH");
            assertThat(json(admin(body(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/jobs/" + name + "/trigger"), Map.of("dryRun", true))), 200)
                    .path("status").asText()).isEqualTo("SUCCEEDED");
        }
    }

    @Test void T_14_20_R_14_16_theSweepErasesTheExpiredMembersAndPurgesWhatItsRetentionEnds() throws Exception {
        // The dry run lists the work and changes nothing.
        var dry = runner.manual(CLUB, JobName.RETENTION_SWEEP, true, "admin-account");
        assertThat(counters(dry)).containsEntry("WOULD_ERASE_RETENTION", 2L).containsEntry("WOULD_ERASE_REJECTED_SIGNUP", 1L)
                .containsEntry("WOULD_DELETE_REMITTANCE_FILE", 1L).containsEntry("WOULD_PURGE_AUDIT", 1L).containsEntry("WOULD_EXPIRE_EXPORT", 1L);
        assertThat(mongo.count(new Query(), "erasure_requests")).isZero();
        assertThat(document("members", "left-2020").getString("firstName")).isEqualTo("Fictional");

        var run = runner.manual(CLUB, JobName.RETENTION_SWEEP, false, "admin-account");
        assertThat(run.errors()).isEmpty();
        assertThat(counters(run)).containsEntry("membersErased", 1L).containsEntry("rejectedSignupsErased", 1L).containsEntry("erasuresBlocked", 1L)
                .containsEntry("remittanceFilesDeleted", 1L).containsEntry("auditEntriesPurged", 1L).containsEntry("exportsExpired", 1L);
        // (a) a leave of 2020 with 6 years of retention: erased now, without margin; one of 2023: kept.
        assertThat(document("members", "left-2020").getString("firstName")).isEqualTo("Abonat suprimit");
        assertThat(document("members", "left-2023").getString("firstName")).isEqualTo("Fictional");
        var retention = mongo.findOne(Query.query(Criteria.where("memberIds").is("left-2020")), ErasureRequest.class);
        assertThat(retention.source()).isEqualTo("RETENTION");
        assertThat(retention.executeAt()).isEqualTo(RUN);
        assertThat(retention.status()).isEqualTo("ERASING");
        // (b, INC-10) a rejected signup after 365 days: erased; one of 100 days: kept.
        assertThat(document("members", "rejected-old").getString("firstName")).isEqualTo("Abonat suprimit");
        assertThat(document("members", "rejected-recent").getString("firstName")).isEqualTo("Fictional");
        // A blocked one stays BLOCKED, visible to the console.
        var blocked = mongo.findOne(Query.query(Criteria.where("memberIds").is("blocked-2020")), ErasureRequest.class);
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.blockReasons()).containsExactly("INVOICES_OPEN");
        assertThat(json(platform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/platform/erasure-requests")
                .param("filter", "clubId:eq:" + CLUB)), 200).path("totalItems").asLong()).isEqualTo(3);
        // N-52 is never sent for a retention erasure.
        assertThat(mongo.count(Query.query(Criteria.where("type").is("AccountErasureRequested").and("payload.source").is("RETENTION")), "domain_events")).isEqualTo(3);
        // (c) the 2020 remittance XML leaves the store; the remittance stays; the 2025 one is kept.
        assertThat(document("remittances", "remittance-2020")).doesNotContainKey("fileKey").containsKey("filePurgedAt").containsEntry("status", "SUBMITTED");
        assertThatThrownBy(() -> exports.open(oldXml).readAllBytes()).isNotNull();
        assertThat(document("remittances", "remittance-2025")).containsEntry("fileKey", "remittances/kept.xml");
        // (d) audit older than audit.retentionYears (6) is purged.
        assertThat(document("audit_entries", "audit-2019")).isNull();
        assertThat(document("audit_entries", "audit-2022")).isNotNull();
        // (e) the expired export is a tombstone.
        assertThat(document("export_jobs", "expired-job")).containsEntry("status", "EXPIRED").doesNotContainKey("fileKey");
        // (f) security events expire by their TTL index (security.eventRetentionDays = 90).
        assertThat(mongo.indexOps("security_events").getIndexInfo()).filteredOn("name", "security_event_retention").singleElement()
                .satisfies(index -> assertThat(index.getExpireAfter()).contains(Duration.ofDays(90)));
        // The other club is untouched, and the run is traced.
        assertThat(document("members", "foreign-2020").getString("firstName")).isEqualTo("Fictional");
        assertThat(mongo.count(Query.query(Criteria.where("type").is("SchedulerRun").and("aggregateId").is(run.id())), "domain_events")).isEqualTo(1);
        // A second run finds nothing more to do but the blocked request's retry.
        var again = runner.manual(CLUB, JobName.RETENTION_SWEEP, false, "admin-account");
        assertThat(counters(again)).containsEntry("membersErased", 0L).containsEntry("rejectedSignupsErased", 0L).containsEntry("erasuresBlocked", 1L)
                .containsEntry("remittanceFilesDeleted", 0L).containsEntry("auditEntriesPurged", 0L);
    }

    void pushSubscription(String clubId, String id, com.agilityhub.core.clubs.messaging.persistence.PushSubscription.Status status, Instant expiredAt) {
        var keys = new com.agilityhub.core.clubs.messaging.persistence.PushSubscription.Keys(com.agilityhub.core.clubs.messaging.support.PushKeyFixtures.p256dh(),
                com.agilityhub.core.clubs.messaging.support.PushKeyFixtures.auth());
        mongo.insert(new com.agilityhub.core.clubs.messaging.persistence.PushSubscription(id, clubId, "account-" + id, "https://push.example.test/" + id, null, keys,
                "iPhone · Safari", "UA", status, 0, null, expiredAt, 0L, Instant.parse("2025-01-01T10:00:00Z"), "account-" + id, Instant.parse("2025-01-01T10:00:00Z"), "account-" + id));
    }

    /**
     * COMM-10 (owner decision of 13 Nov 2026, S14 R-14-16 (h)): the sweep deletes the club's push subscriptions that have been
     * `EXPIRED` for 90 days (endpoint, keys and device go); a more recent expiry, an `ACTIVE` subscription however old, and
     * another club's are kept. The dry run counts them and deletes nothing; a second run finds nothing more.
     */
    @Test void T_14_20_COMM_10_theSweepPurgesPushSubscriptionsExpiredForNinetyDays() throws Exception {
        var expired = com.agilityhub.core.clubs.messaging.persistence.PushSubscription.Status.EXPIRED;
        pushSubscription(CLUB, "push-expired-200", expired, RUN.minus(Duration.ofDays(200)));
        pushSubscription(CLUB, "push-expired-90", expired, RUN.minus(Duration.ofDays(90)));
        pushSubscription(CLUB, "push-expired-89", expired, RUN.minus(Duration.ofDays(89)));
        pushSubscription(CLUB, "push-active", com.agilityhub.core.clubs.messaging.persistence.PushSubscription.Status.ACTIVE, null);
        pushSubscription(OTHER, "push-foreign", expired, RUN.minus(Duration.ofDays(200)));
        var dry = runner.manual(CLUB, JobName.RETENTION_SWEEP, true, "admin-account");
        assertThat(counters(dry)).containsEntry("WOULD_PURGE_EXPIRED_PUSH_SUBSCRIPTION", 2L);
        assertThat(mongo.count(new Query(), "push_subscriptions")).isEqualTo(5);

        var run = runner.manual(CLUB, JobName.RETENTION_SWEEP, false, "admin-account");
        assertThat(run.errors()).isEmpty();
        assertThat(counters(run)).containsEntry("expiredPushSubscriptionsPurged", 2L);
        assertThat(run.items()).filteredOn(item -> item.entityType().equals("PushSubscription")).extracting(JobRun.Item::entityId, JobRun.Item::action)
                .containsExactly(tuple("push-expired-200", "PURGE_EXPIRED_PUSH_SUBSCRIPTION"), tuple("push-expired-90", "PURGE_EXPIRED_PUSH_SUBSCRIPTION"));
        assertThat(document("push_subscriptions", "push-expired-200")).isNull();
        assertThat(document("push_subscriptions", "push-expired-90")).isNull();
        for (String kept : java.util.List.of("push-expired-89", "push-active", "push-foreign")) { assertThat(document("push_subscriptions", kept)).as(kept).isNotNull(); }
        assertThat(counters(runner.manual(CLUB, JobName.RETENTION_SWEEP, false, "admin-account"))).containsEntry("expiredPushSubscriptionsPurged", 0L);
    }

    /**
     * PRIV-02 (owner's decision of 13-11, S14 R-14-16, S15 R-15-09): a manual sweep deletes nothing without a dry run made on the
     * server just before and the explicit confirmation of the counts it showed; and a plan that changed since refuses it too.
     */
    @Test void PRIV_02_aManualSweepDeletesNothingWithoutADryRunAndTheConfirmationOfItsCounts() throws Exception {
        refused(json(admin(body(post(TRIGGER), Map.of("dryRun", false))), 409), "DRY_RUN_REQUIRED");
        nothingDeleted();

        // The dry run returns the counts, among them the audit entries the purge would remove.
        var dry = json(admin(body(post(TRIGGER), Map.of("dryRun", true))), 200);
        var counters = dry.at("/effects/counters");
        assertThat(counters.path("WOULD_ERASE_RETENTION").asLong()).isEqualTo(2);
        assertThat(counters.path("WOULD_ERASE_REJECTED_SIGNUP").asLong()).isEqualTo(1);
        assertThat(counters.path("WOULD_DELETE_REMITTANCE_FILE").asLong()).isEqualTo(1);
        assertThat(counters.path("WOULD_PURGE_AUDIT").asLong()).isEqualTo(1);
        assertThat(counters.path("WOULD_PURGE_AUDIT_ENTRIES").asLong()).isEqualTo(1);
        assertThat(counters.path("WOULD_EXPIRE_EXPORT").asLong()).isEqualTo(1);
        String dryRunId = dry.path("runId").asText();

        // Counts that are not the dry run's, a run that is no dry run of this club, or none: refused, nothing deleted.
        var other = mapper.convertValue(counters, java.util.LinkedHashMap.class);
        other.put("WOULD_ERASE_RETENTION", 1);
        refused(json(admin(body(post(TRIGGER), confirmed(dryRunId, other))), 409), "COUNTS_MISMATCH");
        other.remove("WOULD_PURGE_AUDIT_ENTRIES"); other.put("WOULD_ERASE_RETENTION", 2);
        refused(json(admin(body(post(TRIGGER), confirmed(dryRunId, other))), 409), "COUNTS_MISMATCH");
        refused(json(admin(body(post(TRIGGER), confirmed("unknown-run", counters))), 409), "DRY_RUN_REQUIRED");
        refused(json(platform(body(post("/api/v1/platform/clubs/" + OTHER + "/jobs/retention-sweep/trigger"), confirmed(dryRunId, counters))), 409), "DRY_RUN_REQUIRED");
        nothingDeleted();

        // The data changed after the dry run (one more entry past the audit retention): the plan is not the confirmed one.
        raw("audit_entries", "_id", "audit-2018", "clubId", CLUB, "at", Instant.parse("2018-06-01T10:00:00Z"), "action", "PARAMETER_CHANGED", "entityType", "Parameter",
                "entityId", "y", "changes", java.util.List.of(), "traceId", "t", "actorRole", "ADMIN", "origin", "BACKOFFICE");
        refused(json(admin(body(post(TRIGGER), confirmed(dryRunId, counters))), 409), "PLAN_CHANGED");
        nothingDeleted();
        assertThat(document("audit_entries", "audit-2018")).isNotNull();
        // Only the dry run was audited: a refused execution writes nothing.
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("JOB_TRIGGERED")), "audit_entries")).isEqualTo(1);

        // A new dry run, confirmed with the counts it showed (zeros and the real-run counters are not compared): the sweep runs.
        var again = json(admin(body(post(TRIGGER), Map.of("dryRun", true))), 200);
        assertThat(again.at("/effects/counters/WOULD_PURGE_AUDIT_ENTRIES").asLong()).isEqualTo(2);
        var run = json(admin(body(post(TRIGGER), confirmed(again.path("runId").asText(), again.at("/effects/counters")))), 200);
        assertThat(run.path("dryRun").asBoolean()).isFalse();
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.at("/effects/counters/membersErased").asLong()).isEqualTo(1);
        assertThat(run.at("/effects/counters/auditEntriesPurged").asLong()).isEqualTo(2);
        assertThat(document("members", "left-2020").getString("firstName")).isEqualTo("Abonat suprimit");
        assertThat(document("audit_entries", "audit-2019")).isNull();
        assertThat(document("audit_entries", "audit-2018")).isNull();
        // The same confirmation once more: what it confirmed is gone, so the plan changed and nothing runs.
        refused(json(admin(body(post(TRIGGER), confirmed(again.path("runId").asText(), again.at("/effects/counters")))), 409), "PLAN_CHANGED");
    }

    /** PRIV-02: the confirmed dry run is recent (30 minutes at most), and the console's route asks for it as the club's does. */
    @Test void PRIV_02_aDryRunOlderThanThirtyMinutesConfirmsNothingAndTheConsoleAsksForOneToo() throws Exception {
        var dry = json(admin(body(post(TRIGGER), Map.of("dryRun", true))), 200);
        clock.advance(Duration.ofMinutes(31));
        refused(json(admin(body(post(TRIGGER), confirmed(dry.path("runId").asText(), dry.at("/effects/counters")))), 409), "DRY_RUN_REQUIRED");
        String console = "/api/v1/platform/clubs/" + CLUB + "/jobs/retention-sweep/trigger";
        refused(json(platform(body(post(console), Map.of("dryRun", false))), 409), "DRY_RUN_REQUIRED");
        nothingDeleted();
        var fresh = json(platform(body(post(console), Map.of("dryRun", true))), 200);
        var run = json(platform(body(post(console), confirmed(fresh.path("runId").asText(), fresh.at("/effects/counters")))), 200);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(document("members", "left-2020").getString("firstName")).isEqualTo("Abonat suprimit");
        // Another process needs no confirmation.
        assertThat(json(admin(body(post("/api/v1/jobs/erasures/trigger"), Map.of("dryRun", false))), 200).path("status").asText()).isEqualTo("SUCCEEDED");
    }

    /** PRIV-02: the file of a remittance still GENERATED (not yet submitted to the bank) is never the sweep's, however old. */
    @Test void PRIV_02_theSweepNeverDeletesTheFileOfARemittanceStillGenerated() throws Exception {
        String pending = "remittances/" + CLUB + "/2019-12/" + java.util.UUID.randomUUID() + ".xml";
        var file = Files.createTempFile("remittance", ".xml"); Files.writeString(file, "<Document/>");
        exports.put(pending, file, "application/xml"); Files.delete(file);
        raw("remittances", "_id", "remittance-generated", "clubId", CLUB, "messageId", "msg-2019", "period", "2020-01", "creationAt", Instant.parse("2019-12-20T10:00:00Z"),
                "fileKey", pending, "status", "GENERATED");
        var dry = runner.manual(CLUB, JobName.RETENTION_SWEEP, true, "admin-account");
        assertThat(counters(dry)).containsEntry("WOULD_DELETE_REMITTANCE_FILE", 1L);
        assertThat(dry.items()).extracting(JobRun.Item::entityId).contains("remittance-2020").doesNotContain("remittance-generated");
        var run = runner.manual(CLUB, JobName.RETENTION_SWEEP, false, "admin-account");
        assertThat(counters(run)).containsEntry("remittanceFilesDeleted", 1L);
        assertThat(document("remittances", "remittance-2020")).doesNotContainKey("fileKey");
        assertThat(document("remittances", "remittance-generated")).containsEntry("fileKey", pending).doesNotContainKey("filePurgedAt");
        assertThat(exports.open(pending).readAllBytes()).isNotEmpty();
        // Even named by a forged item, the task finds no file to delete and marks nothing.
        try (var tenant = com.agilityhub.core.shared.application.TenantContext.open(CLUB)) {
            assertThat(remittanceFiles.remittanceFile("remittance-generated")).isNull();
            assertThat(remittanceFiles.remittanceFilePurged("remittance-generated", RUN)).isFalse();
        }
        assertThat(document("remittances", "remittance-generated")).containsEntry("fileKey", pending);
    }

    /**
     * PRIV-02: the retention keys are editable by the club only above their floor (audit 6 years, after leaving 1 year, erasure
     * margin 30 days), and an override stored under its floor (before it existed) is ignored: the sweep uses the product default.
     */
    @Test void PRIV_02_retentionParametersStayAboveTheirFloorAndAStoredValueUnderItIsIgnored() throws Exception {
        for (var floor : Map.of("audit.retentionYears", 6, "rgpd.retentionYearsAfterLeave", 1, "rgpd.erasureMinDaysAfterLeave", 30).entrySet()) {
            String path = "/api/v1/parameters/" + floor.getKey();
            var current = json(admin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)), 200);
            assertThat(current.at("/constraints/min").asInt()).as(floor.getKey()).isEqualTo(floor.getValue());
            var under = json(admin(body(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path),
                    Map.of("value", floor.getValue() - 1, "version", current.path("version").asLong()))), 400);
            assertThat(under.path("code").asText()).isEqualTo("PARAMETER_INVALID");
            var above = json(admin(body(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path),
                    Map.of("value", floor.getValue() + 1, "version", current.path("version").asLong()))), 200);
            assertThat(above.path("value").asInt()).isEqualTo(floor.getValue() + 1);
        }
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "parameters");
        for (String key : java.util.List.of("audit.retentionYears", "rgpd.retentionYearsAfterLeave")) {
            raw("parameters", "_id", CLUB + "-" + key, "clubId", CLUB, "key", key, "value", 0, "type", "int", "scope", "club", "history", java.util.List.of(),
                    "version", 1L, "updatedAt", RUN);
        }
        configs.invalidate(CLUB);
        var dry = runner.manual(CLUB, JobName.RETENTION_SWEEP, true, "admin-account");
        // With 0 the 2023 leave and the 2022 audit entry would be in scope; with the defaults (6 years) they are not.
        assertThat(counters(dry)).containsEntry("WOULD_ERASE_RETENTION", 2L).containsEntry("WOULD_PURGE_AUDIT_ENTRIES", 1L);
        assertThat(dry.parametersSnapshot()).contains(new JobRun.Entry("audit.retentionYears", 6), new JobRun.Entry("rgpd.retentionYearsAfterLeave", 6));
    }
}
