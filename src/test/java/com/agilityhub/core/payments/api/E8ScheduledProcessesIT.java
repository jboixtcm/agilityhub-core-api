package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.PackBalanceService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class E8ScheduledProcessesIT extends BillingItSupport {
    @Autowired JobRunner jobs;
    @Autowired PackBalanceService packs;
    @BeforeEach void cleanProcesses() {
        for (String collection : List.of("pack_balances", "inactivity_periods", "leave_requests", "dog_documents", "job_runs", "job_locks", "job_action_marks")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
    }
    void dog(String id, String member) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("memberId", member).append("name", "Example dog")
                .append("status", "ACTIVE").append("version", 0L).append("createdAt", Date.from(NOW)), "dogs");
    }
    JobRun runJob(JobName name, boolean dry) {
        var result = jobs.manual(CLUB, name, dry, "bill-admin");
        assertThat(result.status()).as("%s errors %s", name, result.errors()).isEqualTo(JobStatus.SUCCEEDED); return result;
    }
    long counter(JobRun run, String name) {
        return run.counters().stream().filter(e -> e.key().equals(name)).mapToLong(e -> ((Number) e.value()).longValue()).findFirst().orElseThrow();
    }
    Map<String, Long> counts() {
        var result = new TreeMap<String, Long>();
        for (String collection : mongo.getCollectionNames()) {
            if (!Set.of("job_runs", "job_locks").contains(collection)) {
                result.put(collection, mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), collection));
            }
        }
        return result;
    }
    @Test void T_15_19_T_15_20_T_15_21_T_15_22_T_12_24_planMatchesEffectsAcrossCensusAndPacks() throws Exception {
        dog("e8-dog-roca", "roca"); dog("e8-dog-puig", "puig"); dog("e8-dog-vila", "vila");
        String expired, warned;
        try (var tenant = TenantContext.open(CLUB)) {
            expired = packs.open("roca", "e8-dog-roca", "bill-plan-pack10", null, LocalDate.of(2026, 4, 1), 10, LocalDate.of(2026, 8, 31), "Example").id();
            warned = packs.open("roca", "e8-dog-roca", "bill-plan-pack10", null, LocalDate.of(2026, 5, 1), 10, LocalDate.of(2026, 9, 15), "Example").id();
        }
        var start = ok(admin(post("/api/v1/inactivity-periods").contentType("application/json")
                .content("{\"memberId\":\"puig\",\"fromMonth\":\"2026-09\",\"toMonth\":\"2026-10\"}")), 201);
        ok(admin(post("/api/v1/members/vila/leave").contentType("application/json").content("{\"effectiveDate\":\"2026-08-31\"}")), 201);
        parameter(CLUB, "messaging.documentReminderDays", 14);
        mongo.insert(new Document("_id", "e8-document").append("clubId", CLUB).append("dogId", "e8-dog-puig").append("type", "HEALTH_CARD")
                .append("state", "PENDING").append("files", List.of()).append("version", 0L).append("createdAt", Date.from(NOW.minusSeconds(30L * 86400))), "dog_documents");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("nuria")), new Update().set("signup.submittedAt", Date.from(NOW.minusSeconds(40L * 86400))), "members");
        clock.setInstant(Instant.parse("2026-09-01T04:00:00Z"));
        var before = counts(); var dry = runJob(JobName.EXPIRATIONS, true);
        assertThat(counts()).containsAllEntriesOf(before);
        assertThat(dry.items()).extracting(JobRun.Item::action).contains("WOULD_EXPIRE_PACK", "WOULD_WARN_PACK", "WOULD_START_INACTIVITY",
                "WOULD_LEAVE", "WOULD_REMIND_SIGNUPS", "WOULD_REMIND_DOCUMENT");
        var real = runJob(JobName.EXPIRATIONS, false);
        assertThat(real.items().stream().map(i -> i.entityId() + ":" + i.action()).toList())
                .containsExactlyElementsOf(dry.items().stream().map(i -> i.entityId() + ":" + i.action().substring(6)).toList());
        assertThat(counter(real, "expiredPacks")).isEqualTo(1); assertThat(counter(real, "warnedPacks")).isEqualTo(1);
        assertThat(counter(real, "expiredSetups")).isZero();
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(packs.get(expired).state().name()).isEqualTo("EXPIRED");
            assertThat(packs.balance("roca", "e8-dog-roca", LocalDate.of(2026, 9, 1)).orElseThrow().id()).isEqualTo(warned);
            assertThat(packs.get(warned).expiryWarnedAt()).isEqualTo(clock.instant());
        }
        assertThat(member("vila", "status")).isEqualTo("LEFT");
        assertThat(mongo.findById(start.path("id").asText(), Document.class, "inactivity_periods").getString("state")).isEqualTo("ACTIVE");
        assertThat(events("SignupPendingAging")).hasSize(1); assertThat(events("DocumentReminderDue")).hasSize(1);
        var again = runJob(JobName.EXPIRATIONS, false);
        assertThat(counter(again, "expiredPacks")).isZero(); assertThat(counter(again, "alreadyDone")).isEqualTo(1);
        assertThat(events("SignupPendingAging")).hasSize(1); assertThat(events("InactivityStarted")).hasSize(1);
    }
    @Test void T_15_28_reminderTargetsNextMonthOnceAndNeverCreatesBusinessRows() throws Exception {
        admin("bill-admin", CLUB); clock.setInstant(Instant.parse("2026-08-22T04:00:00Z"));
        var before = counts(); var dry = runJob(JobName.BILLING_REMINDER, true);
        assertThat(counts()).containsAllEntriesOf(before);
        assertThat(dry.items()).singleElement().satisfies(i -> assertThat(i.detail()).contains(new JobRun.Entry("period", "2026-09")));
        var real = runJob(JobName.BILLING_REMINDER, false);
        assertThat(counter(real, "remittanceReminders")).isEqualTo(1);
        assertThat(events("RemittanceReminderDue")).singleElement().satisfies(e ->
                assertThat(e.get("payload", Document.class)).containsEntry("period", "2026-09"));
        assertThat(counter(runJob(JobName.BILLING_REMINDER, false), "remittanceReminders")).isZero();
        assertThat(invoices()).isEmpty(); assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "remittances")).isZero();
        clock.setInstant(Instant.parse("2026-08-23T04:00:00Z")); assertThat(runJob(JobName.BILLING_REMINDER, false).items()).isEmpty();
        clock.setInstant(Instant.parse("2026-09-22T04:00:00Z")); parameter(CLUB, "billing.remittanceReminderDay", 0);
        assertThat(runJob(JobName.BILLING_REMINDER, false).items()).isEmpty();
        parameter(CLUB, "billing.remittanceReminderDay", 22);
        modules(CLUB, List.of());
        var job = jobs.registered(JobName.BILLING_REMINDER).orElseThrow();
        assertThat(jobs.scheduled(CLUB, true, job, clock.instant()).orElseThrow().skipReason()).isEqualTo(SkipReason.MODULE_OFF);
    }
    @Test void T_15_23_submittedSepaIsSettledOnCollectionDayOnce() throws Exception {
        var result = run("2026-09", simulate("2026-09").path("id").asText());
        String remittance = result.at("/remittance/id").asText();
        ok(admin(keyed(post("/api/v1/remittances/" + remittance + "/submission"), Map.of("submittedAt", "2026-08-25"))), 200);
        assertThat(counter(runJob(JobName.EXPIRATIONS, false), "settledInvoices")).isZero();
        clock.setInstant(Instant.parse("2026-09-01T04:00:00Z"));
        var dry = runJob(JobName.EXPIRATIONS, true);
        assertThat(dry.items()).anySatisfy(i -> assertThat(i.action()).isEqualTo("WOULD_SETTLE"));
        assertThat(counter(runJob(JobName.EXPIRATIONS, false), "settledInvoices")).isEqualTo(4);
        var receipts = invoices().stream().filter(i -> remittance.equals(i.getString("remittanceId"))).toList();
        assertThat(receipts).hasSize(4).allSatisfy(i -> {
            assertThat(i.getString("status")).isEqualTo("PAID");
            assertThat(i.getDate("paidAt").toInstant()).isEqualTo(Instant.parse("2026-08-31T22:00:00Z"));
        });
        assertThat(counter(runJob(JobName.EXPIRATIONS, false), "settledInvoices")).isZero();
        assertThat(events("InvoicePaid")).hasSize(4);
    }
}
