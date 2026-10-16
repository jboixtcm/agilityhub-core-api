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
}
