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
        for (String collection : List.of("inactivity_periods", "leave_requests", "pack_balances")) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        mongo.save(new Document("_id", "s13-monthly").append("clubId", CLUB).append("type", "MONTHLY"), "plans");
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
    @Test void T_13_09_approvalCancelsOnlyBookingsInsideThePeriod() throws Exception {
        var inside = book(as("laura"), "thu", "s08-d-duna");
        var outside = book(as("laura"), "mon", "s08-d-rock");
        // Create an October period by the audited admin override; move the second class beyond the interval.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(outside.path("id").asText())), new Update().set("classStartsAt", Date.from(local("2026-11-02T18:50"))), "bookings");
        var result = call(POST, "/inactivity-periods", Map.of("memberId", "s08-m-laura", "fromMonth", "2026-10", "toMonth", "2026-10", "overrideDeadline", true), as("admin"), 201);
        assertThat(result.path("cancelledBookings")).hasSize(1);
        assertThat(booking(inside.path("id").asText()).getString("state")).isEqualTo("CANCELLED");
        assertThat(booking(outside.path("id").asText()).getString("state")).isEqualTo("ACTIVE");
        assertThat(result.path("feeSnapshot").path("firstMonth").path("amountMinor").asLong()).isEqualTo(2000);
    }
    @Test void T_13_10_disabledCancellationKeepsBookings() throws Exception {
        parameter("inactivity.cancelBookingsOnApproval", false); var b = book(as("laura"), "thu", "s08-d-duna");
        var result = call(POST, "/inactivity-periods", Map.of("memberId", "s08-m-laura", "fromMonth", "2026-10", "toMonth", "2026-10", "overrideDeadline", true), as("admin"), 201);
        assertThat(result.path("cancelledBookings")).isEmpty(); assertThat(booking(b.path("id").asText()).getString("state")).isEqualTo("ACTIVE");
    }
    @Test void T_13_11_approvalInsideThePeriodStartsImmediatelyAndCannotBeDecidedAgain() throws Exception {
        String id = requestPeriod("2026-11", "2026-12"); clock.setInstant(local("2026-11-03T12:00"));
        var approved = call(POST, "/inactivity-periods/" + id + "/decision", Map.of("decision", "APPROVED"), as("admin"), 200);
        assertThat(approved.path("state").asText()).isEqualTo("ACTIVE"); assertThat(events("InactivityStarted")).isEqualTo(1);
        assertThat(call(POST, "/inactivity-periods/" + id + "/decision", Map.of("decision", "DENIED"), as("admin"), 422).path("code").asText()).isEqualTo("INACTIVITY_INVALID_STATE");
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
        assertThat(call(POST, "/me/leave-requests", Map.of("requestedDate", "2026-10-31", "reasonKey", "EXTERNAL"), as("laura"), 422, UUID.randomUUID().toString()).path("code").asText()).isEqualTo("LEAVE_ALREADY_REQUESTED");
        assertThat(count("audit_entries", Criteria.where("action").is("LEAVE_RESOLVED"))).isEqualTo(1);
    }
    @Test void T_13_16_approvedLeaveClosesFutureInactivityAndFixesTheLastInvoiceMonth() throws Exception {
        var period = requestPeriod("2026-12", null); var leave = requestLeave("2026-10-31");
        call(POST, "/leave-requests/" + leave + "/decision", Map.of("decision", "APPROVED"), as("admin"), 200);
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
        modules(Module.INACTIVITY); var result = call(GET, "/me/inactivity-periods", null, as("laura"), 200); assertThat(result.path("fee").isNull()).isTrue();
        modules(); call(GET, "/me/inactivity-periods", null, as("laura"), 404);
        assertThat(call(GET, "/me/leave-requests", null, as("laura"), 200).path("offerInactivity").asBoolean()).isFalse();
    }
    @Test void T_13_26_stalePatchCannotOverwriteANewerEdit() throws Exception {
        var id = requestPeriod("2026-11", null);
        call(PATCH, "/me/inactivity-periods/" + id, Map.of("version", 0, "toMonth", "2026-12"), as("laura"), 200);
        assertThat(call(PATCH, "/me/inactivity-periods/" + id, Map.of("version", 0, "toMonth", "2027-01"), as("laura"), 409).path("code").asText()).isEqualTo("STALE_VERSION");
    }
}
