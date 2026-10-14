package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.census.application.InactivityPeriodService;
import com.agilityhub.core.clubs.census.application.InactivityScheduler;
import com.agilityhub.core.clubs.common.application.SavedViewService;
import com.agilityhub.core.clubs.dashboard.application.DashboardQuery;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E8-T13: real S13 writes, stored states and HTTP responses; no lifecycle port doubles. */
class LifecycleMenuIT extends BookingFixtures {
    @Autowired SavedViewService views;
    @Autowired DashboardQuery dashboard;
    @Autowired InactivityPeriodService inactivity;
    @Autowired InactivityScheduler scheduler;

    @BeforeEach void menuFixtures() {
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "saved_views");
        mongo.save(planDocument("s13-menu-monthly", "MONTHLY"), "plans");
        mongo.updateMulti(Query.query(Criteria.where("clubId").is(CLUB)), new Update().set("planId", "s13-menu-monthly"), "members");
        dashboard.invalidate(CLUB); dashboard.invalidate(OTHER);
    }

    @Test void T_13_22_systemFlagIsReadOnlySharedProtectedAndTenantScoped() throws Exception {
        for (String club : List.of(CLUB, OTHER)) {
            try (var tenant = TenantContext.open(club)) { views.seedPlannedLeaves(); views.seedPlannedLeaves(); }
        }
        account("second-admin", "ADMIN", "s08-m-second-admin");
        String id = CLUB + ":planned-leaves";
        var stored = mongo.findById(id, Document.class, "saved_views");
        for (String admin : List.of("admin", "second-admin")) {
            var listed = call(GET, "/saved-views?listKey=members", null, as(admin), 200);
            assertThat(listed).hasSize(1);
            var view = call(GET, "/saved-views/" + id, null, as(admin), 200);
            assertThat(view).isEqualTo(listed.get(0));
            assertThat(view.path("system").isBoolean()).isTrue();
            assertThat(view.path("system").booleanValue()).isTrue();
            assertThat(view.path("shared").booleanValue()).isTrue();
            assertThat(view.path("name").asText()).isEqualTo("Baixes previstes");
            assertThat(view.path("columns")).isEqualTo(mapper.valueToTree(List.of("fullName", "dogs", "plan", "leaveDate", "leaveSource")));
            assertThat(view.path("filters")).isEqualTo(mapper.valueToTree(List.of(Map.of("field", "displayStatus", "op", "eq", "value", "LEAVE_SCHEDULED"))));
            assertThat(view.path("sort")).isEqualTo(mapper.valueToTree(List.of("leaveDate,asc")));
            var update = mapper.createObjectNode().put("listKey", "members").put("name", "Changed").put("shared", false)
                    .put("system", false).put("version", view.path("version").asLong());
            update.set("columns", view.path("columns")); update.set("filters", view.path("filters")); update.set("sort", view.path("sort"));
            assertThat(call(PUT, "/saved-views/" + id, update, as(admin), 403).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(call(DELETE, "/saved-views/" + id, null, as(admin), 403).path("code").asText()).isEqualTo("FORBIDDEN");
        }
        assertThat(mongo.findById(id, Document.class, "saved_views")).isEqualTo(stored);
        assertThat(call(GET, "/saved-views/" + OTHER + ":planned-leaves", null, as("admin"), 404).path("code").asText()).isEqualTo("NOT_FOUND");
        for (String path : List.of("/saved-views?listKey=members", "/saved-views/" + id)) {
            call(GET, path, null, as("laura"), 403);
        }
    }

    @Test void T_13_22_personalAndSharedViewsCannotClaimSystemStatus() throws Exception {
        for (boolean shared : List.of(false, true)) {
            var input = mapper.createObjectNode().put("listKey", "members").put("name", "Baixes previstes")
                    .put("shared", shared).put("system", true);
            input.set("columns", mapper.valueToTree(List.of("fullName")));
            input.set("filters", mapper.createArrayNode()); input.set("sort", mapper.createArrayNode());
            var created = call(POST, "/saved-views", input, as("admin"), 201);
            assertThat(created.path("system").isBoolean()).isTrue();
            assertThat(created.path("system").booleanValue()).isFalse();
            String path = "/saved-views/" + created.path("id").asText();
            input.put("version", created.path("version").asLong());
            var updated = call(PUT, path, input, as("admin"), 200);
            assertThat(updated.path("system").isBoolean()).isTrue();
            assertThat(updated.path("system").booleanValue()).isFalse();
            assertThat(call(GET, path, null, as("admin"), 200)).isEqualTo(updated);
            assertThat(call(GET, "/saved-views?listKey=members", null, as("admin"), 200).get(0)).isEqualTo(updated);
            call(DELETE, path, null, as("admin"), 204);
        }
    }

    @Test void T_14_29_countersReadCommittedRequestsDecisionsAndWithdrawalsImmediately() throws Exception {
        counters(0, 0);
        String period = period("laura"), second = period("joan");
        String leave = leave();
        counters(2, 1);
        call(POST, "/inactivity-periods/" + period + "/decision", Map.of("decision", "DENIED"), as("admin"), 200);
        counters(1, 1);
        call(POST, "/me/inactivity-periods/" + second + "/cancellation", null, as("joan"), 200);
        counters(0, 1);
        call(POST, "/leave-requests/" + leave + "/decision", Map.of("decision", "DENIED"), as("admin"), 200);
        counters(0, 0);
        leave = leave(); counters(0, 1);
        call(POST, "/me/leave-requests/" + leave + "/cancellation", null, as("pere"), 200);
        counters(0, 0);
        period = period("laura"); leave = leave(); counters(1, 1);
        call(POST, "/inactivity-periods/" + period + "/decision", Map.of("decision", "APPROVED"), as("admin"), 200);
        call(POST, "/leave-requests/" + leave + "/decision", Map.of("decision", "APPROVED", "effectiveDate", "2026-11-30"), as("admin"), 200);
        counters(0, 0);
        clock.setInstant(local("2026-11-01T10:00"));
        scheduler.startDue(CLUB, LocalDate.parse("2026-11-01")); counters(0, 0);
        clock.setInstant(local("2026-12-01T10:00"));
        scheduler.finishDue(CLUB, LocalDate.parse("2026-12-01")); counters(0, 0);
    }

    @Test void T_14_29_expiredRequestsDisappearAndRolledBackWritesStayInvisible() throws Exception {
        period("laura"); counters(1, 0);
        try (var tenant = TenantContext.open(CLUB)) {
            tx.executeWithoutResult(status -> {
                inactivity.request("s08-m-joan", "2026-11", "2026-11", null, false, false);
                assertThatCode(() -> counters(1, 0)).doesNotThrowAnyException();
                status.setRollbackOnly();
            });
        }
        // A cold read as well as the warm cache must exclude the aborted request.
        counters(1, 0); dashboard.invalidate(CLUB); counters(1, 0);
        clock.setInstant(local("2026-12-01T10:00"));
        counters(1, 0);
        assertThat(scheduler.expireStale(CLUB, LocalDate.parse("2026-12-01"))).isEqualTo(1);
        counters(0, 0);
    }

    @Test void T_14_29_disabledInactivityKeepsLeaveCountAndCountersEnforceTenantAndRole() throws Exception {
        period("laura"); leave(); counters(1, 1);
        var foreignMember = new Document(mongo.findById("s08-m-pere", Document.class, "members"));
        foreignMember.put("_id", "s13-foreign-member"); foreignMember.put("clubId", OTHER);
        mongo.save(foreignMember, "members");
        var foreign = new Document(mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "leave_requests"));
        foreign.put("_id", "s13-foreign-leave"); foreign.put("clubId", OTHER); foreign.put("memberId", "s13-foreign-member");
        mongo.save(foreign, "leave_requests");
        dashboard.invalidate(CLUB); counters(1, 1);
        mvc.perform(get("/api/v1/dashboard/counters").header("Host", OTHER_HOST)
                        .with(jwt().jwt(j -> j.subject("other-admin").claim("clubId", OTHER)).authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingInactivityRequests").value(0))
                .andExpect(jsonPath("$.pendingLeaveRequests").value(1)).andExpect(jsonPath("$.pendingRequests").value(1));
        modules(Module.BILLING); dashboard.invalidate(CLUB); counters(0, 1);
        for (String role : List.of("laura", "inst")) { call(GET, "/dashboard/counters", null, as(role), 403); }
        assertThat(call(GET, "/dashboard/counters", null, impersonating("admin", "s08-m-laura"), 403)
                .path("code").asText()).isEqualTo("IMPERSONATION_DENIED");
    }

    String period(String member) throws Exception {
        return call(POST, "/me/inactivity-periods", Map.of("fromMonth", "2026-11", "toMonth", "2026-11"),
                as(member), 201, UUID.randomUUID().toString()).path("id").asText();
    }
    String leave() throws Exception {
        return call(POST, "/me/leave-requests", Map.of("requestedDate", "2026-11-30", "reasonKey", "EXTERNAL"),
                as("pere"), 201, UUID.randomUUID().toString()).path("id").asText();
    }
    void counters(int inactivityCount, int leaveCount) throws Exception {
        JsonNode result = call(GET, "/dashboard/counters", null, as("admin"), 200);
        assertThat(result.path("pendingInactivityRequests").isInt()).isTrue();
        assertThat(result.path("pendingLeaveRequests").isInt()).isTrue();
        assertThat(result.path("pendingInactivityRequests").intValue()).isEqualTo(inactivityCount);
        assertThat(result.path("pendingLeaveRequests").intValue()).isEqualTo(leaveCount);
        assertThat(result.path("pendingRequests").intValue()).isEqualTo(inactivityCount + leaveCount);
    }
}
