package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.training.domain.TrainingBookingState;
import com.agilityhub.core.clubs.training.domain.TrainingOrigin;
import com.agilityhub.core.clubs.training.persistence.TrainingBooking;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E5-T01 contract: every S08/S09/S15 route answers 501 behind its guards (T-08-47, T-09-24, T-15-29 contract halves). */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class E5ContractIT extends AbstractIntegrationTest {
    static final String CLUB = "e5-club-a", OTHER = "e5-club-b", HOST = "e5-a.example.test", OTHER_HOST = "e5-b.example.test";
    static final List<String> ROLES = List.of("ANON", "MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN");
    static final List<String> DATA = List.of("bookings", "seat_holds", "waitlist_entries", "seat_locks", "training_bookings", "job_runs", "job_locks",
            "class_sessions", "members", "memberships", "domain_events", "audit_entries", "idempotency_records", "notifications", "parameters");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired MongoTemplate mongo;
    @Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;

    record Route(String method, String path, List<String> roles, JsonNode body, Map<String, String> params, boolean idempotency, int success,
                 String module, String scope, boolean resource, boolean impersonation) {
        boolean club() { return scope.equals("CLUB"); }
        /** E5-T02 serves the S08 WP-08-B routes, E5-T03 the WP-08-C waiting list, E5-T04 S09, E5-T05 S15; the rest stay 501 until E5-T06. */
        boolean implemented() { return IMPLEMENTED.contains(method + " " + path); }
    }
    static final Set<String> IMPLEMENTED = Set.of("POST /api/v1/seat-holds", "DELETE /api/v1/seat-holds/{id}", "POST /api/v1/bookings",
            "GET /api/v1/me/bookings", "GET /api/v1/bookings/{id}", "GET /api/v1/bookings/{id}/calendar.ics", "POST /api/v1/bookings/{id}/cancellation",
            "GET /api/v1/bookings", "GET /api/v1/class-sessions/{id}/bookings",
            "POST /api/v1/waitlist-entries", "GET /api/v1/waitlist-entries/{id}", "POST /api/v1/waitlist-entries/{id}/cancellation",
            "POST /api/v1/waitlist-entries/{id}/claim", "GET /api/v1/class-sessions/{id}/waitlist-entries",
            // E5-T04 serves every S09 route.
            "GET /api/v1/training-slots", "GET /api/v1/me/training-summary", "GET /api/v1/me/training-bookings", "POST /api/v1/training-bookings",
            "GET /api/v1/training-bookings/{id}", "POST /api/v1/training-bookings/{id}/cancellation", "GET /api/v1/training-bookings",
            "GET /api/v1/training-bookings/export",
            // E5-T05 serves every S15 route.
            "GET /api/v1/jobs", "GET /api/v1/jobs/{name}/runs", "GET /api/v1/jobs/{name}/runs/{runId}", "POST /api/v1/jobs/{name}/trigger",
            "PUT /api/v1/jobs/{name}/switch", "GET /api/v1/risk-review", "GET /api/v1/platform/jobs/overview",
            "POST /api/v1/platform/clubs/{clubId}/jobs/{name}/trigger");
    /** An implemented route passed its guards: whatever business answer it gives, it is not an auth failure, a stub or a crash. */
    private void served(MockHttpServletRequestBuilder request) throws Exception {
        var result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getRequest().getMethod() + " " + result.getRequest().getRequestURI() + " "
                + result.getResponse().getContentAsString()).isNotIn(401, 403, 500, 501);
    }
    static Stream<Route> routes() throws Exception {
        try (var input = E5ContractIT.class.getResourceAsStream("/fixtures/contracts/e5-routes.json")) {
            return Arrays.stream(new ObjectMapper().readValue(input, Route[].class));
        }
    }

    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2026-10-05T08:00:00Z"));
        mongo.remove(Query.query(Criteria.where("_id").in(CLUB, OTHER)), Club.class);
        for (String clubId : List.of(CLUB, OTHER)) { club(clubId, List.of(Module.values())); }
        hosts.invalidate();
        for (String collection : DATA) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        mongo.remove(new Query(), "job_locks");
        mongo.save(new org.bson.Document("_id", "e5-member-a").append("clubId", CLUB).append("accountId", "e5-MEMBER").append("status", "ACTIVE")
                .append("firstName", "Example").append("bookingBlock", Map.of("active", false)), "members");
        mongo.save(new org.bson.Document("_id", "e5-member-membership").append("clubId", CLUB).append("accountId", "e5-MEMBER").append("memberId", "e5-member-a")
                .append("status", "ACTIVE"), "memberships");
        Instant starts = Instant.parse("2026-10-07T16:50:00Z"), ends = Instant.parse("2026-10-07T17:50:00Z"), now = clock.instant();
        var session = new LinkedHashMap<String, Object>();
        session.put("id", "e5-class-a"); session.put("clubId", CLUB); session.put("weekId", "e5-week-a"); session.put("date", "2026-10-07");
        session.put("startTime", "18:50"); session.put("endTime", "19:50"); session.put("startsAt", starts.toString()); session.put("endsAt", ends.toString());
        session.put("state", "ACTIVE"); session.put("levelIds", List.of()); session.put("instructorIds", List.of()); session.put("capacity", 5);
        session.put("capacityMode", "AUTO"); session.put("counters", Map.of("booked", 1, "waiting", 1));
        session.put("risk", Map.of("exempt", false, "notifiedBookingIds", List.of())); session.put("version", 1);
        mongo.insert(mapper.convertValue(session, com.agilityhub.core.clubs.scheduling.persistence.ClassSession.class));
        mongo.insert(new Booking("e5-booking-a", CLUB, "e5-class-a", "dog-a", "e5-member-a", BookingState.ACTIVE, BookingOrigin.APP, now,
                new Booking.Actor("e5-MEMBER", null, "Example"), starts, ends, "2026-10-04", null, null, null, null, null, null,
                null, null, null, null, null, null, null, 1L, now, "e5-MEMBER", now, "e5-MEMBER"));
        mongo.insert(new WaitlistEntry("e5-entry-a", CLUB, "e5-class-a", "dog-b", "e5-member-a", "e5-MEMBER", now, WaitlistState.ACTIVE, 1, null, null,
                null, null, null, starts, "2026-10-04", 1L, now, "e5-MEMBER", now, "e5-MEMBER"));
        mongo.insert(new TrainingBooking("e5-training-a", CLUB, "e5-member-a", "dog-a", "ring-a", Instant.parse("2026-10-06T06:30:00Z"),
                Instant.parse("2026-10-06T07:00:00Z"), "ring-a_2026-10-06T06:30:00Z", 0, Instant.parse("2026-10-04T18:00:00Z"),
                TrainingBookingState.ACTIVE, TrainingOrigin.APP, "e5-MEMBER", null, null, null, null, null, null, null, 1L, now, now, "e5-MEMBER"));
        mongo.insert(new JobRun("e5-run-a", CLUB, JobName.RISK_REVIEW, Instant.parse("2026-10-05T05:30:00Z"), "2026-10-05T07:30", "Europe/Madrid",
                JobTrigger.SCHEDULE, false, JobStatus.SUCCEEDED, null, now, now, 0L, List.of(), List.of(), List.of(), null, List.of(), true, null, false));
    }
    private void club(String clubId, List<Module> modules) {
        mongo.remove(Query.query(Criteria.where("_id").is(clubId)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(clubId, clubId.equals(CLUB) ? HOST : OTHER_HOST));
        tree.set("modules", mapper.valueToTree(modules));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(clubId);
    }

    private String path(Route r, String clubId) {
        String id = r.path().contains("seat-holds") ? "e5-hold-a" : r.path().contains("waitlist-entries/") ? "e5-entry-a"
                : r.path().contains("class-sessions") ? "e5-class-a" : r.path().contains("training-bookings") ? "e5-training-a" : "e5-booking-a";
        return r.path().replace("{id}", id).replace("{name}", "risk-review").replace("{runId}", "e5-run-a").replace("{clubId}", clubId);
    }
    private MockHttpServletRequestBuilder call(Route r, String clubId, String role) throws Exception {
        return call(r, clubId, role, "e5-member-a");
    }
    private MockHttpServletRequestBuilder call(Route r, String clubId, String role, String memberId) throws Exception {
        var request = request(HttpMethod.valueOf(r.method()), path(r, clubId)).header("Host", clubId.equals(CLUB) ? HOST : OTHER_HOST);
        if (r.body() != null && !r.body().isNull()) { request.contentType("application/json").content(mapper.writeValueAsString(r.body())); }
        r.params().forEach(request::param);
        if (r.idempotency()) { request.header("Idempotency-Key", UUID.randomUUID().toString()); }
        if (!role.equals("ANON")) {
            request.with(jwt().jwt(j -> j.subject("e5-" + role).claim("clubId", clubId).claim("memberId", memberId))
                    .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
        }
        return request;
    }
    private ResultActions error(MockHttpServletRequestBuilder request, int status, String... codes) throws Exception {
        var result = mvc.perform(request);
        String label = result.andReturn().getRequest().getMethod() + " " + result.andReturn().getRequest().getRequestURI();
        assertThat(result.andReturn().getResponse().getStatus()).as(label + " " + result.andReturn().getResponse().getContentAsString()).isEqualTo(status);
        assertThat(mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("code").asText()).as(label).isIn((Object[]) codes);
        return result.andExpect(jsonPath("$.traceId").isNotEmpty()).andExpect(jsonPath("$.message").isNotEmpty());
    }

    @ParameterizedTest @MethodSource("routes")
    void T_08_26_T_09_30_T_15_29_everyRouteEnforcesRolesTenantAndResourceIsolationBefore501(Route route) throws Exception {
        for (String role : ROLES) {
            boolean allowed = route.roles().contains(role);
            if (allowed && route.implemented()) { served(call(route, CLUB, role)); continue; }
            error(call(route, CLUB, role), allowed ? 501 : role.equals("ANON") ? 401 : 403,
                    allowed ? "NOT_IMPLEMENTED" : role.equals("ANON") ? "UNAUTHENTICATED" : "FORBIDDEN");
        }
        String role = route.roles().stream().filter(r -> !r.equals("ANON")).findFirst().orElseThrow();
        if (route.club()) {
            error(call(route, CLUB, role).with(req -> { req.removeHeader("Host"); req.addHeader("Host", OTHER_HOST); return req; }), 403, "TENANT_MISMATCH");
            error(call(route, CLUB, role).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))), 403, "NO_MEMBERSHIP");
        }
        if (route.resource()) { error(call(route, OTHER, route.scope().equals("PUBLIC") ? "ANON" : role), 404, "NOT_FOUND"); }
        assertThat(TenantContext.current()).isNull();
    }

    @Test void T_08_27_T_09_23_T_15_08_impersonationIsAcceptedOnlyOnTheMemberRoutes() throws Exception {
        for (String kind : List.of("admin", "member")) {
            String id = "e5-imp-" + kind;
            var role = kind.equals("admin") ? com.agilityhub.core.identity.domain.Role.ADMIN : com.agilityhub.core.identity.domain.Role.MEMBER;
            mongo.save(new com.agilityhub.core.identity.persistence.Account(id, id + "@example.test", "Example " + kind, "en", null,
                    Set.of(), com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
            mongo.save(new com.agilityhub.core.identity.persistence.Membership(id, id, CLUB, "e5-member-a", Set.of(role),
                    com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, role));
        }
        mongo.save(new org.bson.Document("_id", "e5-member-a").append("clubId", CLUB).append("accountId", "e5-imp-member").append("status", "ACTIVE"), "members");
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try (var scope = TenantContext.open(CLUB)) { issued = impersonations.create("e5-imp-admin", "e5-member-a", "Contract authorization test"); }
        for (Route route : routes().toList()) {
            var request = call(route, CLUB, "MEMBER").with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER"));
            if (route.impersonation() && route.implemented()) { served(request); }
            else if (route.impersonation()) { error(request, 501, "NOT_IMPLEMENTED"); }
            else { error(request, 403, "IMPERSONATION_DENIED", "FORBIDDEN"); }
        }
    }

    @Test void T_08_26_T_09_30_memberRoutesRejectStaffAndOtherMembersSeeNothingOfTheirs() throws Exception {
        for (Route route : routes().filter(r -> r.roles().equals(List.of("MEMBER"))).toList()) {
            for (String staff : List.of("ADMIN", "INSTRUCTOR")) {
                error(call(route, CLUB, "MEMBER").with(jwt().jwt(j -> j.claim("clubId", CLUB).claim("memberId", "e5-member-a"))
                        .authorities(() -> "ROLE_MEMBER", () -> "ROLE_" + staff)), 403, "FORBIDDEN");
            }
        }
        for (Route route : routes().filter(r -> r.resource() && r.club() && r.roles().contains("MEMBER")).toList()) {
            error(call(route, CLUB, "MEMBER", "someone-else"), 404, "NOT_FOUND");
        }
        // Staff read another member's booking (served since E5-T02), waiting-list entry (E5-T03) and training booking (E5-T04).
        mvc.perform(get("/api/v1/bookings/e5-booking-a").header("Host", HOST).with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_INSTRUCTOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("e5-booking-a"));
        mvc.perform(get("/api/v1/waitlist-entries/e5-entry-a").header("Host", HOST).with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_INSTRUCTOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("e5-entry-a")).andExpect(jsonPath("$.state").value("ACTIVE"));
        mvc.perform(get("/api/v1/training-bookings/e5-training-a").header("Host", HOST).with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_INSTRUCTOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("e5-training-a")).andExpect(jsonPath("$.state").value("ACTIVE"));
        // T-08-47 / T-09-30: MEMBER on the universal lists → 403.
        for (String path : List.of("/api/v1/bookings", "/api/v1/training-bookings", "/api/v1/risk-review")) {
            error(get(path).header("Host", HOST).with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_MEMBER")), 403, "FORBIDDEN");
        }
    }

    @Test void T_09_31_T_08_19_disabledModulesAnswer404BeforeTheStub() throws Exception {
        club(CLUB, List.of(Module.BILLING));
        for (Route route : routes().filter(r -> r.module() != null).toList()) {
            for (String role : route.roles()) { error(call(route, CLUB, role), 404, "MODULE_DISABLED"); }
        }
        var hold = routes().filter(r -> r.path().equals("/api/v1/seat-holds")).findFirst().orElseThrow();
        error(call(hold, CLUB, "MEMBER").content("{\"classSessionId\":\"e5-class-a\",\"dogId\":\"dog-a\",\"waitlistEntryId\":\"e5-entry-a\"}"), 404, "MODULE_DISABLED");
        error(call(hold, CLUB, "MEMBER"), 404, "NOT_FOUND"); // served since E5-T02: the fixture's class-a does not exist
        // S15: a process whose module is off is 404; an unknown route id is JOB_UNKNOWN (422 by catalog rule 0).
        var admin = jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN");
        error(get("/api/v1/jobs/payment-timeouts/runs").header("Host", HOST).with(admin), 404, "MODULE_DISABLED");
        error(post("/api/v1/jobs/waitlist-fifo/trigger").contentType("application/json").content("{\"dryRun\":true}").header("Host", HOST).with(admin), 404, "MODULE_DISABLED");
        // A catalog process without an implementation yet (P10 is E8) still has its (empty) history once its module is on.
        mvc.perform(get("/api/v1/jobs/billing-reminder/runs").header("Host", HOST).with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        error(post("/api/v1/jobs/billing-reminder/trigger").contentType("application/json").content("{\"dryRun\":true}").header("Host", HOST).with(admin), 422, "JOB_UNKNOWN");
        error(get("/api/v1/jobs/foo/runs").header("Host", HOST).with(admin), 422, "JOB_UNKNOWN");
        error(get("/api/v1/jobs/risk-review/runs/missing").header("Host", HOST).with(admin), 404, "NOT_FOUND");
        error(get("/api/v1/jobs/cleanup/runs/e5-run-a").header("Host", HOST).with(admin), 404, "NOT_FOUND");
        var platform = jwt().authorities(() -> "ROLE_AGILITYHUB_ADMIN");
        error(post("/api/v1/platform/clubs/missing-club/jobs/risk-review/trigger").contentType("application/json").content("{\"dryRun\":false}").with(platform), 404, "NOT_FOUND");
        error(post("/api/v1/platform/clubs/" + CLUB + "/jobs/foo/trigger").contentType("application/json").content("{\"dryRun\":false}").with(platform), 422, "JOB_UNKNOWN");
        error(post("/api/v1/platform/clubs/" + CLUB + "/jobs/payment-timeouts/trigger").contentType("application/json").content("{\"dryRun\":false}").with(platform), 404, "MODULE_DISABLED");
        error(post("/api/v1/platform/clubs/" + CLUB + "/jobs/risk-review/trigger").contentType("application/json").content("{}").with(platform), 400, "VALIDATION_ERROR");
        error(get("/api/v1/bookings/e5-booking-a/calendar.ics").header("Host", HOST), 400, "VALIDATION_ERROR");
    }

    private Map<String, List<org.bson.Document>> database() {
        var result = new TreeMap<String, List<org.bson.Document>>();
        for (String name : mongo.getCollectionNames()) {
            if (name.equals("security_events")) { continue; }
            var documents = mongo.getCollection(name).find().sort(new org.bson.Document("_id", 1)).into(new ArrayList<>());
            if (!documents.isEmpty()) { result.put(name, documents); }
        }
        return result;
    }
    @Test void T_08_47_T_09_24_T_15_29_theStubsWriteNothing() throws Exception {
        var before = database();
        for (Route route : routes().filter(r -> !r.implemented()).toList()) {
            for (String role : route.roles()) { mvc.perform(call(route, CLUB, role)).andExpect(status().isNotImplemented()); }
        }
        assertThat(database()).isEqualTo(before);
    }

    @Test void T_15_29_testClockMovesTheInjectedClockOnlyUnderTheTestProfile() throws Exception {
        mvc.perform(post("/api/v1/test/clock").contentType("application/json").content("{\"advanceSeconds\":90}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.now").value("2026-10-05T08:01:30Z"));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-05T08:01:30Z"));
        mvc.perform(post("/api/v1/test/clock").contentType("application/json").content("{\"instant\":\"2026-10-11T18:00:00Z\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.now").value("2026-10-11T18:00:00Z"));
        error(post("/api/v1/test/clock").contentType("application/json").content("{}"), 400, "VALIDATION_ERROR");
    }

    @Test void T_08_47_T_09_24_T_15_29_snapshotPublishesEveryOperationWithTypedResponsesAndListMetadata() throws Exception {
        var api = mapper.readTree(mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(routes().count()).isEqualTo(32);
        var s15 = routes().filter(r -> r.path().startsWith("/api/v1/jobs") || r.path().startsWith("/api/v1/platform") || r.path().equals("/api/v1/risk-review")).count();
        var s09 = routes().filter(r -> "FREE_TRAINING".equals(r.module())).count();
        assertThat(List.of(32 - s15 - s09, s09, s15)).containsExactly(16L, 8L, 8L);
        for (Route route : routes().toList()) {
            var op = api.path("paths").path(route.path()).path(route.method().toLowerCase());
            assertThat(op.isMissingNode()).as(route.path()).isFalse();
            if (route.implemented()) { assertThat(op.path("description").asText()).as(route.path()).contains("Roles:").doesNotContain("501"); }
            else { assertThat(op.path("description").asText()).as(route.path()).contains("501", "guards", "Roles:"); }
            assertThat(op.path("responses").has(Integer.toString(route.success()))).as(route.path()).isTrue();
            assertThat(op.path("operationId").asText()).as(route.path()).doesNotContain("_");
            // Club routes take the tenant from the JWT; only the platform console addresses a club explicitly.
            if (route.club()) { assertThat(op.path("parameters").findValuesAsText("name")).doesNotContain("clubId"); }
            var key = Stream.of(op.has("parameters") ? mapper.convertValue(op.path("parameters"), JsonNode[].class) : new JsonNode[0]).filter(p -> p.path("name").asText().equals("Idempotency-Key")).findFirst();
            if (route.idempotency()) { assertThat(key.orElseThrow().path("required").asBoolean()).as(route.path()).isTrue(); }
        }
        assertThat(api.at("/paths/~1api~1v1~1training-bookings~1{id}~1cancellation/post/parameters").findValuesAsText("name")).contains("Idempotency-Key");
        for (var entry : Map.of("bookings", "state,dogId,memberId,classSessionId,bookingWeekKey,origin,classStartsAt",
                "training-bookings", "date,ringId,memberId,dogId,state,origin", "jobs/{name}/runs", "status,scheduledFor,trigger,dryRun",
                "ring-blocks", "ringId,kind,reason,state,from,to").entrySet()) {
            assertThat(strings(api.path("paths").path("/api/v1/" + entry.getKey()).at("/get/x-filterable"))).containsExactly(entry.getValue().split(","));
        }
        assertThat(strings(api.at("/paths/~1api~1v1~1bookings/get/x-sortable"))).containsExactly("classStartsAt", "bookedAt");
        assertThat(strings(api.at("/paths/~1api~1v1~1training-bookings/get/x-sortable"))).containsExactly("startsAt");
        assertThat(strings(api.at("/paths/~1api~1v1~1jobs~1{name}~1runs/get/x-sortable"))).containsExactly("scheduledFor", "startedAt");
        assertThat(api.at("/paths/~1api~1v1~1training-bookings/get/x-exportable").asBoolean()).isTrue();
        assertThat(api.at("/paths/~1api~1v1~1bookings~1{id}~1calendar.ics/get/security").isArray()).isTrue();
        assertThat(api.at("/paths/~1api~1v1~1bookings~1{id}~1calendar.ics/get/security")).isEmpty();
        var schemas = api.at("/components/schemas");
        for (String group : List.of("bookings", "training", "jobs")) {
            var fixtures = mapper.readTree(getClass().getResourceAsStream("/fixtures/contracts/e5-" + group + "-responses.json"));
            // SINGLE_CLASS off (the Cànic): the examples carry no price, no payment and no checkout.
            assertThat(fixtures.findParents("price")).isEmpty();
            assertThat(fixtures.findParents("checkoutUrl")).isEmpty();
            fixtures.fields().forEachRemaining(entry -> assertFixtureSchema(schemas, schemas.path(entry.getKey().split("#")[0]), entry.getValue(), entry.getKey()));
        }
        for (String name : List.of("BookingLimitReachedDetails", "ClassFullDetails", "NotYetOpenDetails", "WaitlistLimitDetails", "InactivityPeriodDetails",
                "TrainingLimitReachedDetails", "SlotTakenDetails", "SlotOutOfWindowDetails", "TrainingCancelTooLateDetails", "RingHasBookingsDetails")) {
            assertThat(schemas.path(name).path("properties").isEmpty()).as(name).isFalse();
        }
        assertThat(strings(schemas.at("/BookableClass/required"))).doesNotContain("price", "opensAt", "notBookableReason");
        assertThat(strings(schemas.at("/Booking/required"))).doesNotContain("checkoutUrl", "charge", "pack");
        assertThat(schemas.at("/TrainingBooking/properties").has("seatIndex")).isFalse();
        assertThat(strings(schemas.at("/JobRun/properties/job/enum"))).hasSize(10).doesNotContain("TEST_NOOP");
        assertThat(strings(schemas.at("/JobSummary/properties/jobName/enum"))).hasSize(10).doesNotContain("TEST_NOOP");
        assertThat(schemas.at("/RiskReviewForm/properties").has("minDogs")).isTrue();
        assertThat(schemas.at("/RiskReview/properties").has("count")).as("S14 dashboard card unchanged").isTrue();
        for (String nullable : List.of("MeHome/impersonation", "Booking/cancellation", "BookableClasses/pack", "TrainingRing/setup",
                "SlotCell/block", "TrainingSummary/bookingBlock", "JobSummary/lastRun", "PlatformJobCell/lastRun")) {
            String[] parts = nullable.split("/");
            var field = schemas.path(parts[0]).path("properties").path(parts[1]);
            assertThat(field.path("anyOf").get(0).has("$ref")).as(nullable).isTrue();
            assertThat(field.path("anyOf").get(1).path("type").asText()).as(nullable).isEqualTo("null");
        }
        assertThat(strings(schemas.at("/AuditAction/enum"))).contains("JOB_TRIGGERED");
    }
    private void assertFixtureSchema(JsonNode schemas, JsonNode schema, JsonNode value, String path) {
        if (schema.has("anyOf")) { assertFixtureSchema(schemas, schema.path("anyOf").get(0), value, path); return; }
        if (schema.has("$ref")) {
            assertFixtureSchema(schemas, schemas.path(schema.path("$ref").asText().substring("#/components/schemas/".length())), value, path); return;
        }
        assertThat(schema.isMissingNode()).as(path + " schema").isFalse();
        if (value.isObject() && schema.has("properties")) {
            assertThat(value.fieldNames()).toIterable().as(path + " required").containsAll(strings(schema.path("required")));
            value.fields().forEachRemaining(entry -> {
                assertThat(schema.path("properties").has(entry.getKey())).as(path + "." + entry.getKey()).isTrue();
                assertFixtureSchema(schemas, schema.path("properties").path(entry.getKey()), entry.getValue(), path + "." + entry.getKey());
            });
        } else if (value.isObject() && schema.has("additionalProperties")) {
            value.fields().forEachRemaining(entry -> assertFixtureSchema(schemas, schema.path("additionalProperties"), entry.getValue(), path + "." + entry.getKey()));
        }
        if (value.isArray()) { value.forEach(item -> assertFixtureSchema(schemas, schema.path("items"), item, path + "[]")); }
        if (schema.has("enum")) { assertThat(strings(schema.path("enum"))).as(path).contains(value.asText()); }
    }
    private static List<String> strings(JsonNode node) { var values = new ArrayList<String>(); node.forEach(v -> values.add(v.asText())); return values; }
}
