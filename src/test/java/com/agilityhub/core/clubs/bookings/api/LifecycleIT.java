package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.census.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.support.AuditCovers;
import com.agilityhub.core.platform.application.audit.AuditAction;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;

/** S13 against Mongo, HTTP guards and the real synchronous cancellation services. */
class LifecycleIT extends BookingFixtures {
    @Autowired LeaveScheduler leaveScheduler;
    @Autowired InactivityScheduler inactivityScheduler;
    @Autowired InactivityFeeService fees;
    @Autowired LeaveBillingService leaveBilling;
    @BeforeEach void lifecycle() {
        for (String collection : List.of("inactivity_periods", "leave_requests", "pack_balances", "activities", "activity_registrations")) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        mongo.save(planDocument("s13-monthly", "MONTHLY"), "plans");
        mongo.updateMulti(Query.query(Criteria.where("clubId").is(CLUB)), new Update().set("planId", "s13-monthly"), "members");
    }
    String requestPeriod(String from, String to) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("fromMonth", from); body.put("toMonth", to);
        return call(POST, "/me/inactivity-periods", body, as("laura"), 201, UUID.randomUUID().toString()).path("id").asText();
    }
    String requestLeave(String date) throws Exception {
        return call(POST, "/me/leave-requests", Map.of("requestedDate", date, "reasonKey", "EXTERNAL", "nps", 8), as("laura"), 201, UUID.randomUUID().toString()).path("id").asText();
    }
    @Test @AuditCovers(AuditAction.INACTIVITY_RESOLVED)
    void T_13_08_requestUsesDeadlineAndReplaysTheSameKey() throws Exception {
        clock.setInstant(local("2026-09-24T12:00"));
        String key = UUID.randomUUID().toString(); var body = Map.of("fromMonth", "2026-10", "comments", "A rest");
        var first = call(POST, "/me/inactivity-periods", body, as("laura"), 201, key);
        assertThat(first.path("state").asText()).isEqualTo("REQUESTED");
        assertThat(call(POST, "/me/inactivity-periods", body, as("laura"), 201, key)).isEqualTo(first);
        assertThat(events("InactivityRequested")).isEqualTo(1);
        assertThat(count("audit_entries", Criteria.where("action").is("INACTIVITY_RESOLVED"))).isEqualTo(1);
        clock.setInstant(local("2026-09-26T12:00"));
        var error = call(POST, "/me/inactivity-periods", body, as("laura"), 422, UUID.randomUUID().toString());
        assertThat(error.path("code").asText()).isEqualTo("INACTIVITY_DEADLINE_PASSED");
        assertThat(error.path("details").path("earliestMonth").asText()).isEqualTo("2026-11");
    }
    void otherBookings(String date) {
        var at = Date.from(local(date + "T17:00")); var end = Date.from(local(date + "T18:00"));
        mongo.save(new Document("_id", "s13-training").append("clubId", CLUB).append("memberId", "s08-m-laura").append("dogId", "s08-d-duna")
                .append("ringId", "s08-ring").append("startsAt", at).append("endsAt", end).append("slotId", "s13-slot").append("seatIndex", 0)
                .append("weekStart", at).append("state", "ACTIVE").append("origin", "APP").append("version", 0L).append("createdAt", Date.from(NOW)), "training_bookings");
        mongo.save(new Document("_id", "s13-wait").append("clubId", CLUB).append("memberId", "s08-m-laura").append("dogId", "s08-d-rock")
                .append("classSessionId", "s08-s-fri").append("classStartsAt", at).append("state", "ACTIVE").append("position", 1)
                .append("joinedAt", Date.from(NOW)).append("version", 0L), "waitlist_entries");
        mongo.save(new Document("_id", "s13-activity").append("clubId", CLUB).append("date", date).append("startTime", "17:00").append("endTime", "18:00")
                .append("startsAt", at).append("endsAt", end).append("state", "PUBLISHED").append("type", "OTHER")
                .append("title", new Document("values", new Document("en", "Practice")).append("defaultLocale", "en"))
                .append("waitlistEnabled", false).append("documents", List.of()).append("ringIds", List.of()).append("levelIds", List.of()).append("ringBlockIds", List.of())
                .append("counters", new Document("active", 1).append("waiting", 0)).append("version", 0L), "activities");
        mongo.save(new Document("_id", "s13-registration").append("clubId", CLUB).append("memberId", "s08-m-laura").append("activityId", "s13-activity")
                .append("state", "ACTIVE").append("origin", "APP").append("activityStartsAt", at).append("registeredAt", Date.from(NOW)).append("version", 0L), "activity_registrations");
    }
    @Test void T_13_09_approvalCancelsAllFourKindsAndRefundsOnlyTheOwnersPack() throws Exception {
        openPack("s08-m-laura", "s08-d-duna", 10, 0, LocalDate.parse("2026-11-11"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-laura")), new Update().set("planId", "s08-pack-plan"), "members");
        var inside = book(as("laura"), "thu", "s08-d-duna");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-laura")), new Update().set("planId", "s13-monthly"), "members");
        var outside = book(as("laura"), "mon", "s08-d-rock"); var family = book(as("joan"), "thu", "s08-d-toby");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(outside.path("id").asText())), new Update().set("classStartsAt", Date.from(local("2026-11-02T18:50"))), "bookings");
        otherBookings("2026-10-10");
        var result = call(POST, "/inactivity-periods", Map.of("memberId", "s08-m-laura", "fromMonth", "2026-10", "toMonth", "2026-10", "overrideDeadline", true), as("admin"), 201);
        assertThat(result.path("cancelledBookings")).hasSize(4);
        assertThat(booking(inside.path("id").asText()).getString("state")).isEqualTo("CANCELLED");
        assertThat(booking(outside.path("id").asText()).getString("state")).isEqualTo("ACTIVE");
        assertThat(booking(family.path("id").asText()).getString("state")).isEqualTo("ACTIVE");
        for (var entry : Map.of("s13-training", "training_bookings", "s13-wait", "waitlist_entries", "s13-registration", "activity_registrations").entrySet()) {
            assertThat(mongo.findById(entry.getKey(), Document.class, entry.getValue()).getString("state")).isEqualTo("CANCELLED");
        }
        var pack = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "pack_balances");
        assertThat(pack.getInteger("remaining")).isEqualTo(10); assertThat(pack.get("movements").toString()).contains("CONSUME", "REFUND");
        assertThat(result.path("feeSnapshot").path("firstMonth").path("amountMinor").asLong()).isEqualTo(2000);
        dispatch(); assertThat(count("notifications", Criteria.where("code").is("N-18b"))).isPositive();
        assertThat(count("notifications", Criteria.where("code").is("N-36"))).isZero();
    }
    @Test void T_13_09_lastOwnerFailureRollsBackTheDecisionAndEarlierCancellations() throws Exception {
        var booking = book(as("laura"), "thu", "s08-d-duna"); otherBookings("2026-10-10");
        // A referenced activity disappearing makes the last owner fail after the class and training sweeps.
        mongo.remove(Query.query(Criteria.where("_id").is("s13-activity")), "activities");
        call(POST, "/inactivity-periods", Map.of("memberId", "s08-m-laura", "fromMonth", "2026-10", "toMonth", "2026-10", "overrideDeadline", true), as("admin"), 404);
        assertThat(booking(booking.path("id").asText()).getString("state")).isEqualTo("ACTIVE");
        assertThat(mongo.findById("s13-training", Document.class, "training_bookings").getString("state")).isEqualTo("ACTIVE");
        assertThat(mongo.findById("s13-wait", Document.class, "waitlist_entries").getString("state")).isEqualTo("ACTIVE");
        assertThat(count("inactivity_periods", new Criteria())).isZero(); assertThat(events("InactivityResolved")).isZero();
    }
    @Test void T_13_10_disabledCancellationKeepsBookings() throws Exception {
        parameter("inactivity.cancelBookingsOnApproval", false); var b = book(as("laura"), "thu", "s08-d-duna");
        var result = call(POST, "/inactivity-periods", Map.of("memberId", "s08-m-laura", "fromMonth", "2026-10", "toMonth", "2026-10", "overrideDeadline", true), as("admin"), 201);
        assertThat(result.path("bookingsInside").asInt()).isEqualTo(1); assertThat(result.path("cancelledBookings")).isEmpty(); assertThat(booking(b.path("id").asText()).getString("state")).isEqualTo("ACTIVE");
    }
    @Test void T_13_11_approvalInsideThePeriodStartsImmediatelyAndCannotBeDecidedAgain() throws Exception {
        String id = requestPeriod("2026-11", "2026-12"); clock.setInstant(local("2026-11-03T12:00"));
        var approved = call(POST, "/inactivity-periods/" + id + "/decision", Map.of("decision", "APPROVED"), as("admin"), 200);
        assertThat(approved.path("state").asText()).isEqualTo("ACTIVE"); assertThat(events("InactivityStarted")).isEqualTo(1);
        assertThat(call(POST, "/inactivity-periods/" + id + "/decision", Map.of("decision", "DENIED"), as("admin"), 409).path("code").asText()).isEqualTo("INACTIVITY_INVALID_STATE");
    }
    @Test void T_13_12_adminOverrideIsRecordedAndRequired() throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("memberId", "s08-m-laura"); body.put("fromMonth", "2026-10");
        call(POST, "/inactivity-periods", body, as("admin"), 422); body.put("overrideDeadline", true);
        assertThat(call(POST, "/inactivity-periods", body, as("admin"), 201).path("decision").path("deadlineOverridden").asBoolean()).isTrue();
    }
    @Test void T_13_13_extensionKeepsTheFeeSnapshotAndAppendsHistory() throws Exception {
        var id = requestPeriod("2026-11", "2026-12"); call(POST, "/inactivity-periods/" + id + "/decision", Map.of("decision", "APPROVED"), as("admin"), 200);
        clock.setInstant(local("2026-12-10T12:00"));
        try (var t = TenantContext.open(CLUB)) { inactivityScheduler.startDue(CLUB, LocalDate.of(2026, 12, 10)); }
        var current = call(GET, "/inactivity-periods/" + id, null, as("admin"), 200);
        var changed = call(PATCH, "/me/inactivity-periods/" + id, Map.of("toMonth", "2027-02", "version", current.path("version").asLong()), as("laura"), 200);
        assertThat(changed.path("history")).hasSize(1); assertThat(events("InactivityChanged")).isEqualTo(1);
        parameter("billing.inactivityFeeFirstMonth", Map.of("amountMinor", 2500, "currency", "EUR"));
        try (var t = TenantContext.open(CLUB)) {
            assertThat(fees.feeFor("s08-m-laura", YearMonth.of(2026, 11)).orElseThrow().amountMinor()).isEqualTo(2000);
            assertThat(fees.feeFor("s08-m-laura", YearMonth.of(2027, 2)).orElseThrow().amountMinor()).isEqualTo(1000);
        }
    }
    @Test void T_13_14_withdrawalRespectsTheApprovedDeadline() throws Exception {
        var id = requestPeriod("2026-11", null); call(POST, "/inactivity-periods/" + id + "/decision", Map.of("decision", "APPROVED"), as("admin"), 200);
        clock.setInstant(local("2026-10-26T12:00")); call(POST, "/me/inactivity-periods/" + id + "/cancellation", null, as("laura"), 422);
        clock.setInstant(local("2026-10-20T12:00"));
        assertThat(call(POST, "/me/inactivity-periods/" + id + "/cancellation", null, as("laura"), 200).path("state").asText()).isEqualTo("CANCELLED");
    }
    @Test @AuditCovers(AuditAction.LEAVE_RESOLVED)
    void T_13_15_onePendingRequestAndDisabledNps() throws Exception {
        requestLeave("2026-10-31");
        assertThat(call(POST, "/me/leave-requests", Map.of("requestedDate", "2026-10-31", "reasonKey", "EXTERNAL"), as("laura"), 409, UUID.randomUUID().toString()).path("code").asText()).isEqualTo("LEAVE_ALREADY_REQUESTED");
        assertThat(count("audit_entries", Criteria.where("action").is("LEAVE_RESOLVED"))).isEqualTo(1);
    }
    @Test void T_13_16_approvedLeaveClosesFutureInactivityAndFixesTheLastInvoiceMonth() throws Exception {
        var before = book(as("laura"), "thu", "s08-d-duna"); var after = book(as("laura"), "mon", "s08-d-rock");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(after.path("id").asText())), new Update().set("classStartsAt", Date.from(local("2026-11-02T18:50"))), "bookings");
        otherBookings("2026-11-03");
        var period = requestPeriod("2026-12", null); var leave = requestLeave("2026-10-31");
        call(POST, "/leave-requests/" + leave + "/decision", Map.of("decision", "APPROVED"), as("admin"), 200);
        assertThat(booking(before.path("id").asText()).getString("state")).isEqualTo("ACTIVE");
        assertThat(booking(after.path("id").asText()).getString("state")).isEqualTo("CANCELLED");
        assertThat(call(GET, "/inactivity-periods/" + period, null, as("admin"), 200).path("cancelReason").asText()).isEqualTo("LEAVE");
        try (var t = TenantContext.open(CLUB)) { assertThat(leaveBilling.lastInvoicedMonth("s08-m-laura")).contains(YearMonth.of(2026, 10)); }
    }
    @Test void T_13_17_leaveTakesEffectTheNextDayAndRunsOnce() throws Exception {
        var leave = requestLeave("2026-10-06"); call(POST, "/leave-requests/" + leave + "/decision", Map.of("decision", "APPROVED"), as("admin"), 200);
        assertThat(leaveScheduler.executeDue(CLUB, LocalDate.of(2026, 10, 6))).isZero();
        clock.setInstant(local("2026-10-07T12:00")); assertThat(leaveScheduler.executeDue(CLUB, LocalDate.of(2026, 10, 7))).isEqualTo(1);
        assertThat(leaveScheduler.executeDue(CLUB, LocalDate.of(2026, 10, 7))).isZero();
    }
    @Test @AuditCovers(AuditAction.LEAVE_CANCELLED)
    void T_13_18_T_13_19_directLeaveSupersedesPendingAndCancellationClearsTheDate() throws Exception {
        var pending = requestLeave("2026-10-31");
        call(POST, "/members/s08-m-laura/leave", Map.of("effectiveDate", "2026-12-31", "reasonKey", "CLUB_DECISION"), as("admin"), 201);
        assertThat(call(GET, "/leave-requests/" + pending, null, as("admin"), 200).path("cancelledBy").asText()).isEqualTo("ADMIN");
        call(DELETE, "/members/s08-m-laura/planned-leave", null, as("admin"), 204, UUID.randomUUID().toString());
        try (var t = TenantContext.open(CLUB)) { assertThat(leaveBilling.lastInvoicedMonth("s08-m-laura")).isEmpty(); }
        assertThat(count("audit_entries", Criteria.where("action").is("LEAVE_CANCELLED"))).isPositive();
    }
    @Test void T_13_24_memberCannotReachAFamilyMembersPeriodAndInstructorIsForbidden() throws Exception {
        var id = requestPeriod("2026-11", null);
        call(PATCH, "/me/inactivity-periods/" + id, Map.of("version", 0, "toMonth", "2026-12"), as("joan"), 404);
        call(GET, "/inactivity-periods/" + id, null, as("inst"), 403);
        call(GET, "/leave-requests", null, as("laura"), 403);
    }
    @Test void T_13_25_billingOffOmitsFeesAndInactivityOffBlocksTheRoute() throws Exception {
        requestPeriod("2026-11", null);
        assertThat(call(GET, "/members?filter=hasPendingRequest:eq:true", null, as("admin"), 200).path("items")).hasSize(1);
        modules(Module.INACTIVITY); var result = call(GET, "/me/inactivity-periods", null, as("laura"), 200); assertThat(result.path("fee").isNull()).isTrue();
        modules(); call(GET, "/me/inactivity-periods", null, as("laura"), 404);
        assertThat(call(GET, "/me/leave-requests", null, as("laura"), 200).path("offerInactivity").asBoolean()).isFalse();
        assertThat(call(GET, "/members?filter=hasPendingRequest:eq:true", null, as("admin"), 200).path("items")).isEmpty();
    }
    @Test void T_13_26_stalePatchCannotOverwriteANewerEdit() throws Exception {
        var id = requestPeriod("2026-11", null);
        call(PATCH, "/me/inactivity-periods/" + id, Map.of("version", 0, "toMonth", "2026-12"), as("laura"), 200);
        assertThat(call(PATCH, "/me/inactivity-periods/" + id, Map.of("version", 0, "toMonth", "2027-01"), as("laura"), 409).path("code").asText()).isEqualTo("STALE_VERSION");
    }

    @Autowired com.agilityhub.core.payments.application.PackBalanceService packService;
    @Autowired com.agilityhub.core.clubs.common.application.SavedViewService savedViews;
    @Autowired com.agilityhub.core.clubs.census.application.LeaveRequestService leaves;

    @Test @AuditCovers(AuditAction.PACK_ADJUSTED)
    void T_12_07_adjustWritesTheBalanceAndItsAuditInOneTransaction() throws Exception {
        openPack("s08-m-laura", "s08-d-duna", 10, 0, LocalDate.parse("2026-11-11"));
        String id = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "pack_balances").getString("_id");
        var result = call(POST, "/pack-balances/"+id+"/adjustments", Map.of("delta", -2, "reason", "Correct a migrated balance"), as("admin"), 200, UUID.randomUUID().toString());
        assertThat(result.path("remaining").asInt()).isEqualTo(8);
        assertThat(result.path("movements").get(0).path("type").asText()).isEqualTo("ADJUST");
        var audit = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("PACK_ADJUSTED")), Document.class, "audit_entries");
        assertThat(audit).isNotNull(); assertThat(audit.get("changes").toString()).contains("remaining", "10", "8");
        call(POST, "/pack-balances/"+id+"/adjustments", Map.of("delta", -9, "reason", "Rejected"), as("admin"), 422, UUID.randomUUID().toString());
        assertThat(count("audit_entries", Criteria.where("action").is("PACK_ADJUSTED"))).isEqualTo(1);
    }
    @Test @AuditCovers(AuditAction.MEMBER_PLAN_CHANGED)
    void T_13_20_packToMonthlyWritesDiscountedDueEntryAndAuditsWithoutMovingInvoiceDate() throws Exception {
        openPack("s08-m-laura", "s08-d-duna", 10, 0, LocalDate.parse("2026-11-11"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-laura")), new Update().set("planId", "s08-pack-plan").set("nextInvoiceDate", "2026-12-01"), "members");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s13-monthly")), new Update().set("active", true).set("entryFee", new Document("mode", "AMOUNT").append("amount", new Document("amountMinor", 10000L).append("currency", "EUR"))), "plans");
        mongo.save(new Document("_id", "s13-price").append("clubId", CLUB).append("planId", "s13-monthly").append("version", 0L), "prices");
        String key = UUID.randomUUID().toString(); var body = Map.of("planId", "s13-monthly", "priceId", "s13-price");
        call(POST, "/members/s08-m-laura/plan-change", body, as("admin"), 204, key);
        call(POST, "/members/s08-m-laura/plan-change", body, as("admin"), 204, key);
        var payment = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("concept").is("ENTRY_FEE")), Document.class, "upfront_payments");
        assertThat(payment.getString("status")).isEqualTo("DUE"); assertThat(payment.get("amountDue", Document.class).get("amountMinor", Number.class).longValue()).isEqualTo(6000);
        assertThat(count("upfront_payments", Criteria.where("concept").is("ENTRY_FEE"))).isEqualTo(1);
        assertThat(count("audit_entries", Criteria.where("action").is("MEMBER_PLAN_CHANGED"))).isEqualTo(1);
        assertThat(mongo.findById("s08-m-laura", Document.class, "members").getString("nextInvoiceDate")).isEqualTo("2026-12-01");
        for (String role : List.of("laura", "inst")) { call(POST, "/members/s08-m-laura/plan-change", body, as(role), 403, UUID.randomUUID().toString()); }
        call(POST, "/members/s08-m-laura/plan-change", Map.of("planId", "unknown", "priceId", "s13-price"), as("admin"), 404, UUID.randomUUID().toString());
    }
    @Test void T_13_20_expirySchedulesOneLeaveAndRenewalCancelsIt() throws Exception {
        openPack("s08-m-laura", "s08-d-duna", 10, 0, LocalDate.parse("2026-11-12"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-laura")), new Update().set("planId", "s08-pack-plan"), "members");
        String id = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "pack_balances").getString("_id");
        try (var t = TenantContext.open(CLUB)) {
            packService.expire(id); dispatch(); dispatch();
            assertThat(leaves.ofMember("s08-m-laura")).singleElement().satisfies(r -> {
                assertThat(r.source().name()).isEqualTo("PACK_EXPIRED"); assertThat(r.decision().effectiveDate()).isEqualTo("2026-12-12");
            });
            packService.open("s08-m-laura", "s08-d-duna", "s08-pack-plan", null, LocalDate.parse("2026-11-20"), null, null, "Renewal");
            dispatch(); dispatch();
            assertThat(leaves.ofMember("s08-m-laura").getFirst().cancelReason().name()).isEqualTo("PACK_RENEWED");
        }
        assertThat(mongo.findById("s08-m-laura", Document.class, "members").get("leaveDate")).isNull();
    }
    @Test void T_13_21_reactivationKeepsNumberAndInactiveDogsAndDoesNotChargeEntry() throws Exception {
        var leave = requestLeave("2026-10-06"); call(POST, "/leave-requests/"+leave+"/decision", Map.of("decision", "APPROVED"), as("admin"), 200);
        clock.setInstant(local("2026-10-07T12:00")); leaveScheduler.executeDue(CLUB, LocalDate.parse("2026-10-07")); dispatch();
        int number = mongo.findById("s08-m-laura", Document.class, "members").getInteger("memberNumber");
        mongo.save(new Document("_id", "s13-price").append("clubId", CLUB).append("planId", "s13-monthly").append("version", 0L), "prices");
        call(POST, "/members/s08-m-laura/reactivation", Map.of(), as("admin"), 400);
        var response = call(POST, "/members/s08-m-laura/reactivation", Map.of("planId", "s13-monthly", "priceId", "s13-price", "nextInvoiceDate", "2026-11-01"), as("admin"), 200);
        assertThat(response.path("status").asText()).isEqualTo("ACTIVE"); assertThat(response.path("memberNumber").asInt()).isEqualTo(number);
        assertThat(mongo.findById("s08-d-duna", Document.class, "dogs").getString("status")).isEqualTo("INACTIVE");
        assertThat(count("upfront_payments", Criteria.where("memberId").is("s08-m-laura"))).isZero();
        assertThat(mongo.findById("s08-m-laura", Document.class, "members").getList("leaveHistory", Document.class)).hasSize(1);
    }
    @Test void T_13_22_plannedLeaveFiltersOverviewAndProtectedSystemView() throws Exception {
        call(POST, "/members/s08-m-laura/leave", Map.of("effectiveDate", "2026-10-31"), as("admin"), 201);
        var result = call(GET, "/members?filter=displayStatus:eq:LEAVE_SCHEDULED&sort=leaveDate,asc", null, as("admin"), 200);
        assertThat(result.path("items")).hasSize(1);
        assertThat(call(GET, "/members/s08-m-laura/overview", null, as("admin"), 200).path("plannedLeave").path("source").asText()).isEqualTo("ADMIN");
        try (var t = TenantContext.open(CLUB)) { savedViews.seedPlannedLeaves(); }
        call(DELETE, "/saved-views/"+CLUB+":planned-leaves", null, as("admin"), 403);
        assertThat(call(GET, "/members?filter=leaveSource:eq:ADMIN", null, as("admin"), 200).path("items")).hasSize(1);
    }
    @Test void T_13_23_previewHasMonthlyFeesAndOmitsDisabledModuleCounts() throws Exception {
        var preview = call(GET, "/me/inactivity-periods/preview?fromMonth=2026-11&toMonth=2026-12", null, as("laura"), 200);
        assertThat(preview.path("feeSchedule")).hasSize(2);
        assertThat(preview.path("feeSchedule").get(0).path("amount").path("amountMinor").asLong()).isEqualTo(2000);
        assertThat(preview.path("feeSchedule").get(1).path("amount").path("amountMinor").asLong()).isEqualTo(1000);
        modules(Module.INACTIVITY, Module.BILLING);
        preview = call(GET, "/me/inactivity-periods/preview?fromMonth=2026-11", null, as("laura"), 200);
        assertThat(preview.path("bookingsInside").has("waitlist")).isFalse();
        assertThat(preview.path("bookingsInside").has("trainings")).isFalse();
        assertThat(preview.path("bookingsInside").has("activities")).isFalse();
    }
    @Test @AuditCovers(AuditAction.UPFRONT_PAYMENT_RECORDED)
    void T_12_07_manualPaymentOpensOnlyWhenFullyPaidAndRecordsTheWholeMovementCycle() throws Exception {
        openPack("s08-m-laura", "s08-d-duna", 10, 0, LocalDate.parse("2026-11-11"));
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "pack_balances");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-laura")), new Update().set("planId", "s08-pack-plan"), "members");
        var body = new LinkedHashMap<String,Object>(); body.put("memberId", "s08-m-laura"); body.put("dogId", "s08-d-duna"); body.put("concept", "PACK");
        body.put("amountDue", Map.of("amountMinor", 10000, "currency", "EUR")); body.put("amountPaid", Map.of("amountMinor", 5000, "currency", "EUR"));
        body.put("channel", "CASH"); body.put("paidAt", "2026-10-06");
        var partial = call(POST, "/upfront-payments", body, as("admin"), 201, UUID.randomUUID().toString());
        assertThat(partial.path("status").asText()).isEqualTo("PARTIAL"); assertThat(count("pack_balances", new Criteria())).isZero();
        body.put("amountPaid", body.get("amountDue"));
        var paid = call(POST, "/upfront-payments", body, as("admin"), 201, UUID.randomUUID().toString());
        assertThat(paid.path("status").asText()).isEqualTo("PAID"); assertThat(count("pack_balances", new Criteria())).isEqualTo(1);
        String pack = paid.path("packBalanceId").asText();
        try (var t = TenantContext.open(CLUB)) {
            packService.consume("s08-m-laura", "s08-d-duna", "s13-cycle", LocalDate.parse("2026-10-08"));
            packService.refund("s13-cycle"); packService.expire(pack);
        }
        var adjusted = call(POST, "/pack-balances/"+pack+"/adjustments", Map.of("delta", 1, "reason", "Reopen", "expiresOn", "2027-05-01"), as("admin"), 200, UUID.randomUUID().toString());
        assertThat(adjusted.path("remaining").asInt()).isEqualTo(11); assertThat(adjusted.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(adjusted.path("movements")).hasSize(5);
        for (var m : adjusted.path("movements")) { System.out.println("PackBalance movement="+m.path("type").asText()+" delta="+m.path("delta").asInt()+" id=[truncated]"); }
        assertThat(count("audit_entries", Criteria.where("action").is("UPFRONT_PAYMENT_RECORDED"))).isEqualTo(2);
        body.put("amountPaid", Map.of("amountMinor", 10001, "currency", "EUR"));
        assertThat(call(POST, "/upfront-payments", body, as("admin"), 422, UUID.randomUUID().toString()).path("code").asText()).isEqualTo("AMOUNT_EXCEEDS_DUE");
    }

    @Test void T_13_26_twoConcurrentDecisionsCommitOnlyOneApproval() throws Exception {
        var id = requestPeriod("2026-11", "2026-12"); var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) { results.add(pool.submit(() -> {
                gate.await(); return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/inactivity-periods/"+id+"/decision")
                        .header("Host", HOST).with(as("admin")).contentType("application/json").content("{\"decision\":\"APPROVED\"}")).andReturn().getResponse().getStatus();
            })); }
            gate.countDown(); var statuses = new ArrayList<Integer>(); for (var result : results) { statuses.add(result.get()); }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(events("InactivityResolved")).isEqualTo(1);
    }
    @Test void T_13_15_invalidLeaveInputsAndWithdrawalKeepTheStoredStateConsistent() throws Exception {
        call(POST, "/me/leave-requests", Map.of("requestedDate", "2026-10-05", "reasonKey", "EXTERNAL"), as("laura"), 422, UUID.randomUUID().toString());
        call(POST, "/me/leave-requests", Map.of("requestedDate", "2026-10-31", "reasonKey", "UNKNOWN"), as("laura"), 422, UUID.randomUUID().toString());
        parameter("leave.npsEnabled", false);
        assertThat(call(POST, "/me/leave-requests", Map.of("requestedDate", "2026-10-31", "reasonKey", "EXTERNAL", "nps", 8), as("laura"), 403, UUID.randomUUID().toString()).path("code").asText()).isEqualTo("READ_ONLY");
        var r = call(POST, "/me/leave-requests", Map.of("requestedDate", "2026-10-31", "reasonKey", "EXTERNAL"), as("laura"), 201, UUID.randomUUID().toString());
        assertThat(call(POST, "/me/leave-requests/"+r.path("id").asText()+"/cancellation", null, as("laura"), 200).path("state").asText()).isEqualTo("CANCELLED");
    }

}
