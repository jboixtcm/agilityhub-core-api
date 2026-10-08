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
    Map<String, List<Document>> snapshot() {
        var result = new TreeMap<String, List<Document>>();
        for (String collection : mongo.getCollectionNames()) {
            if (!Set.of("job_runs", "job_locks").contains(collection)) {
                var documents = mongo.find(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, collection);
                if (!documents.isEmpty()) { result.put(collection, documents); }
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
        var before = snapshot(); var dry = runJob(JobName.EXPIRATIONS, true);
        assertThat(snapshot()).isEqualTo(before);
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
        var before = snapshot(); var dry = runJob(JobName.BILLING_REMINDER, true);
        assertThat(snapshot()).isEqualTo(before);
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
        clock.setInstant(Instant.parse("2026-08-31T21:59:00Z"));
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

    JobRun expirationsAt(String instant, boolean dry) { clock.setInstant(Instant.parse(instant)); return runJob(JobName.EXPIRATIONS, dry); }
    List<String> actions(JobRun run) { return run.items().stream().map(i -> i.entityId() + ":" + i.action()).toList(); }
    long notices(String code) {
        outbox.dispatch();
        return mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("code").is(code)), "notifications");
    }
    List<Document> eventsOf(String type, String aggregateId) {
        return events(type).stream().filter(e -> aggregateId.equals(e.getString("aggregateId"))).toList();
    }
    String period(String member, String from, String to) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("memberId", member); body.put("fromMonth", from); body.put("toMonth", to);
        return ok(admin(post("/api/v1/inactivity-periods").contentType("application/json").content(mapper.writeValueAsString(body))), 201).path("id").asText();
    }
    /** The member's fictional AgilityHub ID account (`bill-account-{id}`), so that a MEMBER notice has its recipient. */
    void account(String member) {
        mongo.save(new com.agilityhub.core.identity.persistence.Account("bill-account-" + member, member + "@example.test", "Example " + member, "ca",
                null, Set.of(), com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, NOW));
    }
    String state(String period) { return mongo.findById(period, Document.class, "inactivity_periods").getString("state"); }
    void pending(String id, int number, String first, Instant submittedAt) {
        status(id, number, first, "Example", "PENDING");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("signup.submittedAt", Date.from(submittedAt)), "members");
    }

    /**
     * T-15-19 (R-15-15a): on 01-09 06:00 a pack ending 15-09 (14 days out) gets one `PackExpiring` and N-11b; the next day (13 days
     * out) nothing more; the pack whose last day was 31-08 is `EXPIRED` with `PackExpired`, its remaining sessions lost; with
     * `PACKS` off the step is skipped with zero counters while the other steps still run.
     */
    @Test void T_15_19_aPackIsWarnedOnceFourteenDaysOutAndExpiresTheDayAfterItsLastDay() throws Exception {
        for (String dog : List.of("e8-dog-a", "e8-dog-b", "e8-dog-c", "e8-dog-d")) { dog(dog, "roca"); }
        String warned, later, expired, untouched;
        try (var tenant = TenantContext.open(CLUB)) {
            warned = packs.open("roca", "e8-dog-a", "bill-plan-pack10", null, LocalDate.of(2026, 5, 1), 10, LocalDate.of(2026, 9, 15), "Example").id();
            later = packs.open("roca", "e8-dog-b", "bill-plan-pack10", null, LocalDate.of(2026, 5, 1), 10, LocalDate.of(2026, 9, 16), "Example").id();
            expired = packs.open("roca", "e8-dog-c", "bill-plan-pack10", null, LocalDate.of(2026, 3, 1), 10, LocalDate.of(2026, 8, 31), "Example").id();
        }
        admin("bill-admin", CLUB); account("roca");
        var dry = expirationsAt("2026-09-01T04:00:00Z", true);
        assertThat(actions(dry)).contains(warned + ":WOULD_WARN_PACK", expired + ":WOULD_EXPIRE_PACK").noneMatch(a -> a.startsWith(later));
        var real = runJob(JobName.EXPIRATIONS, false);
        assertThat(counter(real, "warnedPacks")).isEqualTo(1); assertThat(counter(real, "expiredPacks")).isEqualTo(1);
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(packs.get(warned).expiryWarnedAt()).isEqualTo(clock.instant()); assertThat(packs.get(later).expiryWarnedAt()).isNull();
            var gone = packs.get(expired);
            assertThat(gone.state().name()).isEqualTo("EXPIRED"); assertThat(gone.remaining()).isEqualTo(10);
            // B11: the sessions left at expiry are lost: the balance offers none.
            assertThat(packs.balance("roca", "e8-dog-c", LocalDate.of(2026, 9, 1))).hasValueSatisfying(b -> assertThat(b.remaining()).isZero());
        }
        assertThat(eventsOf("PackExpiring", warned)).hasSize(1); assertThat(eventsOf("PackExpired", expired)).hasSize(1);
        assertThat(notices("N-11b")).isEqualTo(2);
        for (String type : List.of("PackExpiring", "PackExpired")) {
            String eventId = events(type).getFirst().getString("_id");
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-11b").and("eventId").is(eventId)), "notifications"))
                    .as(type + " notice once").isEqualTo(1);
        }
        var next = expirationsAt("2026-09-02T04:00:00Z", false);
        assertThat(actions(next)).noneMatch(a -> a.startsWith(warned) || a.startsWith(expired)).contains(later + ":WARN_PACK");
        assertThat(eventsOf("PackExpiring", warned)).hasSize(1); assertThat(eventsOf("PackExpired", expired)).hasSize(1);
        try (var tenant = TenantContext.open(CLUB)) {
            untouched = packs.open("roca", "e8-dog-d", "bill-plan-pack10", null, LocalDate.of(2026, 3, 1), 10, LocalDate.of(2026, 9, 1), "Example").id();
        }
        modules(CLUB, Arrays.stream(Module.values()).filter(m -> m != Module.PACKS).toList());
        var off = expirationsAt("2026-09-10T04:00:00Z", false);
        assertThat(counter(off, "warnedPacks")).isZero(); assertThat(counter(off, "expiredPacks")).isZero();
        assertThat(actions(off)).noneMatch(a -> a.startsWith(untouched));
        assertThat(mongo.findById(untouched, Document.class, "pack_balances").getString("state")).isEqualTo("ACTIVE");
    }

    /** T-12-24 (S12 schedulers contract): `PackExpired` twice → one `EXPIRED`; a second P5 run expires nothing more. */
    @Test void T_12_24_expiringAPackTwiceGivesOneExpiry() throws Exception {
        dog("e8-dog-a", "roca"); String pack;
        try (var tenant = TenantContext.open(CLUB)) {
            pack = packs.open("roca", "e8-dog-a", "bill-plan-pack10", null, LocalDate.of(2026, 3, 1), 10, LocalDate.of(2026, 8, 31), "Example").id();
        }
        assertThat(counter(expirationsAt("2026-09-01T04:00:00Z", false), "expiredPacks")).isEqualTo(1);
        try (var tenant = TenantContext.open(CLUB)) { packs.expire(pack); }
        assertThat(counter(runJob(JobName.EXPIRATIONS, false), "expiredPacks")).isZero();
        assertThat(eventsOf("PackExpired", pack)).hasSize(1);
        assertThat(mongo.findById(pack, Document.class, "pack_balances").getList("movements", Document.class))
                .filteredOn(m -> "EXPIRE".equals(m.getString("type"))).hasSize(1);
    }

    /**
     * T-15-20 (R-15-15b) and T-13-27 (R-13-05, R-13-13): an approved Oct–Nov period starts on 01-10 once even when run twice;
     * an open one never ends; a `REQUESTED` Oct–Oct with no decision is `CANCELLED{SYSTEM, EXPIRED}` on 01-11; two days late
     * on 03-12 the Oct–Nov one is `FINISHED` with one `InactivityEnded` (N-18c), and a second run changes nothing.
     */
    @Test void T_15_20_T_13_27_inactivityStartsAndEndsOnceAndALateRunGivesTheSameResult() throws Exception {
        account("puig"); String closed = period("puig", "2026-10", "2026-11"), open = period("serra-joan", "2026-10", null);
        var now = clock.instant();
        mongo.insert(new com.agilityhub.core.clubs.census.persistence.inactivity.InactivityPeriod("e8-requested", CLUB, "vidal", "2026-10", "2026-10",
                "Fictional request", com.agilityhub.core.clubs.census.domain.InactivityState.REQUESTED, com.agilityhub.core.clubs.census.domain.LifecycleOrigin.APP, now,
                new com.agilityhub.core.clubs.census.persistence.LifecycleParts.Requester("bill-account-vidal", null), null, null, null, null, null, null, null, null,
                List.of(), List.of(), 0L, now, now));
        assertThat(List.of(state(closed), state(open))).containsOnly("APPROVED");
        expirationsAt("2026-09-30T21:59:00Z", false);
        assertThat(state(closed)).isEqualTo("APPROVED");
        var first = expirationsAt("2026-10-01T04:00:00Z", false);
        assertThat(counter(first, "startedInactivity")).isEqualTo(2);
        assertThat(List.of(state(closed), state(open))).containsOnly("ACTIVE");
        assertThat(counter(runJob(JobName.EXPIRATIONS, false), "startedInactivity")).isZero();
        assertThat(eventsOf("InactivityStarted", closed)).hasSize(1);
        var november = expirationsAt("2026-11-01T04:00:00Z", false);
        assertThat(counter(november, "cancelledInactivity")).isEqualTo(1); assertThat(counter(november, "endedInactivity")).isZero();
        var cancelled = mongo.findById("e8-requested", Document.class, "inactivity_periods");
        assertThat(cancelled.getString("state")).isEqualTo("CANCELLED");
        assertThat(cancelled.toJson()).contains("SYSTEM", "EXPIRED");
        var late = expirationsAt("2026-12-03T04:00:00Z", false);
        assertThat(counter(late, "endedInactivity")).isEqualTo(1);
        assertThat(state(closed)).isEqualTo("FINISHED"); assertThat(state(open)).isEqualTo("ACTIVE");
        assertThat(counter(runJob(JobName.EXPIRATIONS, false), "endedInactivity")).isZero();
        assertThat(eventsOf("InactivityEnded", closed)).hasSize(1); assertThat(events("InactivityEnded")).hasSize(1);
        // N-18c reaches the member once (InactivityStarted has no notice).
        assertThat(notices("N-18c")).isEqualTo(1);
    }

    /**
     * T-15-21 (R-15-15c) and T-13-28 (R-13-13): Joan Vila's `leaveDate` is 31-08. At 23:59 local on 31-08 nothing happens;
     * at 2026-08-31T22:30Z (00:30 of 01-09 in Madrid) he is `LEFT` with `MemberStatusChanged{effectiveDate 2026-08-31}`; again →
     * nothing. A member whose leave date is 01-09 and whose next run comes on 03-09 (a lost day) leaves once, on her date.
     */
    @Test void T_15_21_T_13_28_aLeaveRunsTheDayAfterInTheClubZoneOnceEvenAfterALostDay() throws Exception {
        ok(admin(post("/api/v1/members/vila/leave").contentType("application/json").content("{\"effectiveDate\":\"2026-08-31\"}")), 201);
        ok(admin(post("/api/v1/members/vives/leave").contentType("application/json").content("{\"effectiveDate\":\"2026-09-01\"}")), 201);
        var evening = expirationsAt("2026-08-31T21:59:00Z", false);
        assertThat(counter(evening, "leftMembers")).isZero(); assertThat(member("vila", "status")).isEqualTo("ACTIVE");
        var dry = expirationsAt("2026-08-31T22:30:00Z", true);
        assertThat(dry.items()).filteredOn(i -> i.action().equals("WOULD_LEAVE")).singleElement()
                .satisfies(i -> assertThat(i.detail()).contains(new JobRun.Entry("memberId", "vila"), new JobRun.Entry("futureBookings", 0)));
        var night = runJob(JobName.EXPIRATIONS, false);
        assertThat(counter(night, "leftMembers")).isEqualTo(1); assertThat(member("vila", "status")).isEqualTo("LEFT");
        assertThat(eventsOf("MemberStatusChanged", "vila")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class))
                .containsEntry("effectiveDate", "2026-08-31").containsEntry("before", "ACTIVE").containsEntry("after", "LEFT"));
        assertThat(counter(runJob(JobName.EXPIRATIONS, false), "leftMembers")).isZero();
        assertThat(member("vives", "status")).isEqualTo("ACTIVE");
        var lost = expirationsAt("2026-09-03T04:00:00Z", false);
        assertThat(counter(lost, "leftMembers")).isEqualTo(1); assertThat(member("vives", "status")).isEqualTo("LEFT");
        assertThat(eventsOf("MemberStatusChanged", "vives")).singleElement()
                .satisfies(e -> assertThat(e.get("payload", Document.class)).containsEntry("effectiveDate", "2026-09-01"));
        assertThat(eventsOf("MemberStatusChanged", "vila")).hasSize(1);
    }

    /**
     * T-15-22 (R-15-15d/e): three pending signups of 31, 40 and 5 local days → one `SignupPendingAging{count 2, oldestDays 40}`
     * and one N-34; [Run now] the same day → `alreadyDone`. `documentReminderDays = 0` → no `DocumentReminderDue`; `= 15` with a
     * card pending for 15 days → N-23 and `lastReminderAt`; a manual reminder yesterday → nothing today.
     */
    @Test void T_15_22_signupsAgeIntoOneReminderAndDocumentRemindersFollowTheParameter() throws Exception {
        admin("bill-admin", CLUB);
        var today = Instant.parse("2026-09-01T04:00:00Z");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("nuria")), new Update().set("signup.submittedAt", Date.from(today.minusSeconds(31L * 86400))), "members");
        pending("e8-pending-40", 230, "Berta", today.minusSeconds(40L * 86400));
        pending("e8-pending-5", 231, "Clara", today.minusSeconds(5L * 86400));
        dog("e8-dog-puig", "puig"); dog("e8-dog-torres", "torres"); account("puig");
        mongo.insert(new Document("_id", "e8-document-due").append("clubId", CLUB).append("dogId", "e8-dog-puig").append("type", "HEALTH_CARD")
                .append("state", "PENDING").append("files", List.of()).append("version", 0L).append("createdAt", Date.from(today.minusSeconds(15L * 86400))), "dog_documents");
        mongo.insert(new Document("_id", "e8-document-reminded").append("clubId", CLUB).append("dogId", "e8-dog-torres").append("type", "HEALTH_CARD")
                .append("state", "PENDING").append("files", List.of()).append("version", 0L).append("createdAt", Date.from(today.minusSeconds(60L * 86400)))
                .append("lastReminderAt", Date.from(today.minusSeconds(86400))), "dog_documents");
        var silent = expirationsAt("2026-09-01T04:00:00Z", false);
        assertThat(counter(silent, "documentReminders")).isZero(); assertThat(events("DocumentReminderDue")).isEmpty();
        assertThat(counter(silent, "signupReminders")).isEqualTo(1);
        assertThat(events("SignupPendingAging")).singleElement().satisfies(e -> {
            var payload = e.get("payload", Document.class);
            assertThat(payload).containsEntry("count", 2).containsEntry("oldestDays", 40L);
            assertThat(payload.getList("memberIds", String.class)).containsExactly("e8-pending-40", "nuria");
        });
        var again = runJob(JobName.EXPIRATIONS, false);
        assertThat(counter(again, "alreadyDone")).isEqualTo(1); assertThat(counter(again, "signupReminders")).isZero();
        assertThat(events("SignupPendingAging")).hasSize(1);
        assertThat(notices("N-34")).isEqualTo(1); long n34 = notices("N-34");
        assertThat(runJob(JobName.EXPIRATIONS, true).items()).noneMatch(i -> i.action().equals("WOULD_REMIND_SIGNUPS"));
        parameter(CLUB, "messaging.documentReminderDays", 15);
        var reminded = runJob(JobName.EXPIRATIONS, false);
        assertThat(counter(reminded, "documentReminders")).isEqualTo(1);
        assertThat(events("DocumentReminderDue")).singleElement().satisfies(e -> assertThat(e.getString("aggregateId")).isEqualTo("e8-document-due"));
        assertThat(mongo.findById("e8-document-due", Document.class, "dog_documents").getDate("lastReminderAt").toInstant()).isEqualTo(clock.instant());
        assertThat(mongo.findById("e8-document-reminded", Document.class, "dog_documents").getDate("lastReminderAt").toInstant()).isEqualTo(today.minusSeconds(86400));
        assertThat(counter(runJob(JobName.EXPIRATIONS, false), "documentReminders")).isZero();
        assertThat(notices("N-23")).isEqualTo(1);
        assertThat(notices("N-34")).isEqualTo(n34);
    }

    /** T-15-23 (R-15-15f): the courses vertical (S16) is not built, so step f is registered with a zero counter, with or without COURSES. */
    @Test void T_15_23_theRingSetupStepReportsAZeroCounterUntilTheCoursesVerticalExists() {
        assertThat(counter(expirationsAt("2026-09-01T04:00:00Z", false), "expiredSetups")).isZero();
        modules(CLUB, Arrays.stream(Module.values()).filter(m -> m != Module.COURSES).toList());
        var off = runJob(JobName.EXPIRATIONS, false);
        assertThat(counter(off, "expiredSetups")).isZero(); assertThat(off.items()).noneMatch(i -> i.action().contains("SETUP"));
    }

    /**
     * T-15-28 (R-15-20): with the September remittance generated nothing is sent on 22-08; once it is `ROLLED_BACK` the reminder
     * goes out, N-41 reaches the admins once, and a third run the same day sends nothing.
     */
    @Test void T_15_28_aGeneratedRemittanceSilencesTheReminderAndARolledBackOneDoesNot() throws Exception {
        admin("bill-admin", CLUB); clock.setInstant(Instant.parse("2026-08-22T04:00:00Z"));
        var fee = new com.agilityhub.core.shared.domain.Money(6000, "EUR"); var now = clock.instant();
        mongo.insert(new com.agilityhub.core.payments.persistence.Remittance("e8-remittance", CLUB, "e8-run", "2026-09", "bill-a-2026-09-1", now, "2026-09-01",
                new com.agilityhub.core.payments.persistence.Remittance.Creditor("Club Example", "ES00ZZZB00000000", "ES0000000000000000000000", null), List.of(), 1, fee,
                new com.agilityhub.core.payments.persistence.Remittance.SequenceBreakdown(0, 1), "remittances/bill-a/2026-09-1.xml", now, null,
                com.agilityhub.core.payments.domain.RemittanceStatus.GENERATED, null, null, 0L, now, "bill-admin"));
        assertThat(counter(runJob(JobName.BILLING_REMINDER, false), "remittanceReminders")).isZero();
        mongo.updateFirst(Query.query(Criteria.where("_id").is("e8-remittance")), new Update().set("status", "ROLLED_BACK"), "remittances");
        var dry = runJob(JobName.BILLING_REMINDER, true); var real = runJob(JobName.BILLING_REMINDER, false);
        assertThat(actions(real)).containsExactlyElementsOf(actions(dry).stream().map(a -> a.replace("WOULD_", "")).toList());
        assertThat(counter(real, "remittanceReminders")).isEqualTo(1);
        assertThat(events("RemittanceReminderDue")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class))
                .containsEntry("period", "2026-09").containsEntry("pendingMembers", 10L));
        assertThat(notices("N-41")).isEqualTo(1);
        assertThat(counter(runJob(JobName.BILLING_REMINDER, false), "remittanceReminders")).isZero();
        assertThat(notices("N-41")).isEqualTo(1);
    }

    /** E8-T06 step 3 (R-15-02/03/05): the switch and the club's status skip a scheduled P5 and P10; a late P5 tick catches up. */
    @Test void T_15_06_switchesAndClubStatusSkipTheScheduledRunsAndALateTickCatchesUp() {
        var expirations = jobs.registered(JobName.EXPIRATIONS).orElseThrow(); var reminder = jobs.registered(JobName.BILLING_REMINDER).orElseThrow();
        var late = Instant.parse("2026-09-01T09:00:00Z");
        assertThat(jobs.scheduled(CLUB, false, expirations, late).orElseThrow().skipReason()).isEqualTo(SkipReason.CLUB_INACTIVE);
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "job_runs");
        parameter(CLUB, "jobs.expirations.enabled", false);
        assertThat(jobs.scheduled(CLUB, true, expirations, late).orElseThrow().skipReason()).isEqualTo(SkipReason.DISABLED);
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "job_runs");
        parameter(CLUB, "jobs.expirations.enabled", true);
        var caught = jobs.scheduled(CLUB, true, expirations, late).orElseThrow();
        assertThat(caught.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(caught.scheduledFor()).isEqualTo(Instant.parse("2026-09-01T04:00:00Z"));
        parameter(CLUB, "jobs.billingReminder.enabled", false);
        assertThat(jobs.scheduled(CLUB, true, reminder, Instant.parse("2026-08-22T04:00:00Z")).orElseThrow().skipReason()).isEqualTo(SkipReason.DISABLED);
    }
}
