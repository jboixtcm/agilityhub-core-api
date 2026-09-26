package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.followup.domain.*;
import com.agilityhub.core.clubs.followup.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.bson.Document;
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

/**
 * E6-T01 contract (S10 WP-10-A): every S10 route is published with typed forms (T-10-21) behind its tenant, role,
 * impersonation, module and resource guards (the role matrix half of T-10-22); the routes E6-T02 serves (`implemented` in
 * `e6-routes.json`) answer their success status there, the others still 501. The S10 attachment purposes, the S06
 * attendance status and the P3/P8 catalog rows behave as the contract says.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class E6ContractIT extends AbstractIntegrationTest {
    static final String CLUB = "e6-club-a", OTHER = "e6-club-b", HOST = "e6-a.example.test", OTHER_HOST = "e6-b.example.test";
    static final List<String> ROLES = List.of("ANON", "MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN");
    static final List<String> DATA = List.of("class_sessions", "members", "memberships", "dogs", "instructors", "rings", "tasks", "attachments", "followup_items",
            "followup_read_marks", "attendances", "upload_grants", "domain_events", "audit_entries", "idempotency_records", "notifications", "parameters",
            "bookings", "seat_locks");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired MongoTemplate mongo;
    @Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;

    /** @param implemented served since E6-T02 (the attendance and instructor routes): an allowed role gets `success`, not 501 */
    record Route(String method, String path, List<String> roles, JsonNode body, Map<String, String> params, boolean idempotency, int success,
                 String module, String scope, boolean resource, boolean impersonation, boolean implemented) {
        boolean club() { return scope.equals("CLUB"); }
    }
    static Stream<Route> routes() throws Exception {
        try (var input = E6ContractIT.class.getResourceAsStream("/fixtures/contracts/e6-routes.json")) {
            return Arrays.stream(new ObjectMapper().readValue(input, Route[].class));
        }
    }

    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2026-08-03T06:00:00Z"));
        mongo.remove(Query.query(Criteria.where("_id").in(CLUB, OTHER)), Club.class);
        for (String clubId : List.of(CLUB, OTHER)) { club(clubId, List.of(Module.values())); }
        hosts.invalidate();
        for (String collection : DATA) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        Instant now = clock.instant();
        member("e6-member-a", "e6-MEMBER"); member("e6-member-b", "e6-other-member");
        mongo.save(new Document("_id", "e6-member-membership").append("clubId", CLUB).append("accountId", "e6-MEMBER").append("memberId", "e6-member-a")
                .append("status", "ACTIVE"), "memberships");
        dog("e6-dog-a", "e6-member-a"); dog("e6-dog-b", "e6-member-b");
        mongo.save(new Document("_id", "e6-ring-a").append("clubId", CLUB).append("name", "Central").append("shortName", "CEN").append("color", "#8FCE8F")
                .append("allowsFreeTraining", true).append("active", true).append("order", 1).append("version", 0), "rings");
        mongo.save(new Document("_id", "e6-instructor-a").append("clubId", CLUB).append("memberId", "e6-member-i").append("shortName", "Estel").append("color", "#123456")
                .append("active", true).append("version", 0), "instructors");
        var session = new LinkedHashMap<String, Object>();
        session.put("id", "e6-class-a"); session.put("clubId", CLUB); session.put("weekId", "e6-week-a"); session.put("date", "2026-08-03");
        session.put("startTime", "08:30"); session.put("endTime", "09:30"); session.put("startsAt", "2026-08-03T06:30:00Z"); session.put("endsAt", "2026-08-03T07:30:00Z");
        session.put("state", "ACTIVE"); session.put("levelIds", List.of()); session.put("instructorIds", List.of("e6-instructor-a")); session.put("capacity", 5);
        session.put("capacityMode", "AUTO"); session.put("counters", Map.of("booked", 4, "waiting", 1));
        session.put("risk", Map.of("exempt", false, "notifiedBookingIds", List.of())); session.put("version", 1);
        mongo.insert(mapper.convertValue(session, com.agilityhub.core.clubs.scheduling.persistence.ClassSession.class));
        // E6-T02: a live booking of the class, so the served PUT of the matrix is a no-op 200 (PENDING = its current state).
        mongo.save(new Document("_id", "e6-booking-a").append("clubId", CLUB).append("classSessionId", "e6-class-a").append("dogId", "e6-dog-a")
                .append("memberId", "e6-member-a").append("state", "ACTIVE").append("origin", "APP").append("bookedAt", now)
                .append("classStartsAt", Instant.parse("2026-08-03T06:30:00Z")).append("classEndsAt", Instant.parse("2026-08-03T07:30:00Z")).append("version", 0), "bookings");
        mongo.insert(new Task("e6-task-a", CLUB, "e6-dog-a", "e6-member-a", "Practiqueu el balancí", TaskState.PENDING,
                new Task.Actor("e6-INSTRUCTOR", AuthorRole.INSTRUCTOR, "Estel"), null, null, null, now, now, null, null, 1, 1L));
        mongo.insert(new Attachment("e6-att-task", CLUB, "TASK", "e6-task-a", "e6-att-task", "vídeo.mp4", "video/mp4", 4, "e6-INSTRUCTOR", now, now, null, null, 0));
        mongo.insert(new Attachment("e6-att-note", CLUB, "INSTRUCTOR_NOTE", "e6-dog-a", "e6-att-note", "foto.jpg", "image/jpeg", 4, "e6-MEMBER", now, now, null, null, 0));
        mongo.insert(new FollowupItem("e6-item-a", CLUB, FollowupKind.TASK, "e6-task-a", "e6-dog-a", "e6-member-a", "e6-INSTRUCTOR", AuthorRole.INSTRUCTOR,
                "Estel", "Practiqueu el balancí", now, null, now, false, now));
    }
    private void club(String clubId, List<Module> modules) {
        mongo.remove(Query.query(Criteria.where("_id").is(clubId)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(clubId, clubId.equals(CLUB) ? HOST : OTHER_HOST));
        tree.set("modules", mapper.valueToTree(modules));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(clubId);
    }
    private void member(String id, String account) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("accountId", account).append("status", "ACTIVE").append("firstName", "Example")
                .append("lastName1", "Example").append("bookingBlock", Map.of("active", false)).append("version", 0), "members");
    }
    private void dog(String id, String memberId) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("memberId", memberId).append("name", "Duna").append("sex", "FEMALE")
                .append("status", "ACTIVE").append("version", 0), "dogs");
    }

    private String path(Route r, String clubId) {
        String id = r.path().contains("class-sessions") ? "e6-class-a" : r.path().contains("/dogs/") ? "e6-dog-a" : r.path().contains("/tasks/") ? "e6-task-a"
                : r.path().contains("/attachments/") ? "e6-att-task" : "e6-item-a";
        return r.path().replace("{id}", id);
    }
    private MockHttpServletRequestBuilder call(Route r, String clubId, String role) throws Exception { return call(r, clubId, role, "e6-member-a"); }
    private MockHttpServletRequestBuilder call(Route r, String clubId, String role, String memberId) throws Exception {
        var request = request(HttpMethod.valueOf(r.method()), path(r, clubId)).header("Host", clubId.equals(CLUB) ? HOST : OTHER_HOST);
        if (r.body() != null && !r.body().isNull()) { request.contentType("application/json").content(mapper.writeValueAsString(r.body())); }
        r.params().forEach(request::param);
        if (r.idempotency()) { request.header("Idempotency-Key", UUID.randomUUID().toString()); }
        if (!role.equals("ANON")) {
            request.with(jwt().jwt(j -> j.subject("e6-" + role).claim("clubId", clubId).claim("memberId", memberId))
                    .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
        }
        return request;
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String... roles) {
        return request.header("Host", HOST).with(jwt().jwt(j -> j.subject("e6-" + roles[0]).claim("clubId", CLUB).claim("memberId", "e6-member-a"))
                .authorities(Arrays.stream(roles).map(role -> (org.springframework.security.core.GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }
    private ResultActions error(MockHttpServletRequestBuilder request, int status, String... codes) throws Exception {
        var result = mvc.perform(request);
        String label = result.andReturn().getRequest().getMethod() + " " + result.andReturn().getRequest().getRequestURI();
        assertThat(result.andReturn().getResponse().getStatus()).as(label + " " + result.andReturn().getResponse().getContentAsString()).isEqualTo(status);
        assertThat(mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("code").asText()).as(label).isIn((Object[]) codes);
        return result.andExpect(jsonPath("$.traceId").isNotEmpty()).andExpect(jsonPath("$.message").isNotEmpty());
    }

    /** The status of a served route (E6-T02), whatever its body (JSON or the D12 PDF). */
    private void served(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    }

    @ParameterizedTest @MethodSource("routes")
    void T_10_22_everyRouteEnforcesRolesTenantAndResourceIsolationBefore501(Route route) throws Exception {
        for (String role : ROLES) {
            boolean allowed = route.roles().contains(role);
            if (allowed && route.implemented()) { served(call(route, CLUB, role), route.success()); continue; }
            error(call(route, CLUB, role), allowed ? 501 : role.equals("ANON") ? 401 : 403,
                    allowed ? "NOT_IMPLEMENTED" : role.equals("ANON") ? "UNAUTHENTICATED" : "FORBIDDEN");
        }
        String role = route.roles().getFirst();
        error(call(route, CLUB, role).with(req -> { req.removeHeader("Host"); req.addHeader("Host", OTHER_HOST); return req; }), 403, "TENANT_MISMATCH");
        error(call(route, CLUB, role).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))), 403, "NO_MEMBERSHIP");
        if (route.resource()) { error(call(route, OTHER, role), 404, "NOT_FOUND"); }
        assertThat(TenantContext.current()).isNull();
    }

    @Test void T_10_22_impersonationIsAcceptedOnlyOnHistoryTaskListCompletionAndTheMemberAttachments() throws Exception {
        var issued = impersonate();
        for (Route route : routes().toList()) {
            var request = call(route, CLUB, "MEMBER").with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER"));
            if (route.impersonation() && route.implemented()) { served(request, route.success()); }
            else if (route.impersonation()) { error(request, 501, "NOT_IMPLEMENTED"); }
            else { error(request, 403, "IMPERSONATION_DENIED", "FORBIDDEN"); }
        }
        assertThat(routes().filter(Route::impersonation).map(r -> r.method() + " " + r.path()).toList()).containsExactly("GET /api/v1/me/history",
                "GET /api/v1/tasks", "POST /api/v1/tasks/{id}/completion", "GET /api/v1/attachments");
        var token = jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER");
        // The member's own INSTRUCTOR_NOTE: listed and removed as the member (501 until E6-T03); staff entities are IMPERSONATION_DENIED.
        error(delete("/api/v1/attachments/e6-att-note").header("Host", HOST).header("Idempotency-Key", UUID.randomUUID().toString()).with(token), 501, "NOT_IMPLEMENTED");
        error(get("/api/v1/attachments").param("entityType", "INSTRUCTOR_NOTE").param("entityId", "e6-dog-a").header("Host", HOST).with(token), 501, "NOT_IMPLEMENTED");
        error(get("/api/v1/attachments").param("entityType", "DOG_OBSERVATIONS").param("entityId", "e6-dog-a").header("Host", HOST).with(token), 404, "NOT_FOUND");
        error(post("/api/v1/attachments/upload-url").contentType("application/json").content("{\"purpose\":\"TASK\",\"fileName\":\"a.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":4}")
                .header("Host", HOST).with(token), 403, "IMPERSONATION_DENIED");
        error(post("/api/v1/attachments").contentType("application/json").content("{\"entityType\":\"TASK\",\"entityId\":\"e6-task-a\",\"fileKey\":\"k\",\"name\":\"a.pdf\"}")
                .header("Host", HOST).with(token), 403, "IMPERSONATION_DENIED");
    }
    private com.agilityhub.core.identity.application.ImpersonationService.Issued impersonate() {
        for (String kind : List.of("admin", "member")) {
            String id = "e6-imp-" + kind;
            var role = kind.equals("admin") ? com.agilityhub.core.identity.domain.Role.ADMIN : com.agilityhub.core.identity.domain.Role.MEMBER;
            mongo.save(new com.agilityhub.core.identity.persistence.Account(id, id + "@example.test", "Example " + kind, "en", null,
                    Set.of(), com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
            mongo.save(new com.agilityhub.core.identity.persistence.Membership(id, id, CLUB, "e6-member-a", Set.of(role),
                    com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, role));
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is("e6-member-a")), new org.springframework.data.mongodb.core.query.Update().set("accountId", "e6-imp-member"), "members");
        try (var scope = TenantContext.open(CLUB)) { return impersonations.create("e6-imp-admin", "e6-member-a", "Contract authorization test"); }
    }

    @Test void T_10_15_T_10_19_T_10_22_memberRoutesRejectStaffAndOtherMembersSeeNothingOfTheirs() throws Exception {
        var history = routes().filter(r -> r.path().equals("/api/v1/me/history")).findFirst().orElseThrow();
        for (String staff : List.of("ADMIN", "INSTRUCTOR")) { error(as(get("/api/v1/me/history"), "MEMBER", staff), 403, "FORBIDDEN"); }
        error(call(history, CLUB, "MEMBER", "e6-member-b"), 404, "DOG_NOT_ACCESSIBLE");
        served(as(get("/api/v1/me/history").param("dogId", "e6-dog-a"), "MEMBER"), 200);
        for (Route route : routes().filter(r -> r.resource() && r.roles().contains("MEMBER")).toList()) {
            error(call(route, CLUB, "MEMBER", "e6-member-b"), 404, "NOT_FOUND");
        }
        // GET /tasks?dogId: another member's dog (also a family-group one, S10 §13-13) → 404 DOG_NOT_ACCESSIBLE; staff see every dog of the club.
        error(as(get("/api/v1/tasks").param("dogId", "e6-dog-b"), "MEMBER"), 404, "DOG_NOT_ACCESSIBLE");
        error(as(get("/api/v1/tasks").param("dogId", "e6-dog-b"), "INSTRUCTOR"), 501, "NOT_IMPLEMENTED");
        error(as(get("/api/v1/tasks").param("dogId", "e6-dog-x"), "INSTRUCTOR"), 404, "NOT_FOUND");
        error(as(get("/api/v1/tasks").param("dogId", "e6-dog-a").param("includeDeleted", "true"), "INSTRUCTOR"), 403, "FORBIDDEN");
        error(as(get("/api/v1/tasks").param("dogId", "e6-dog-a").param("includeDeleted", "true"), "MEMBER"), 403, "FORBIDDEN");
        error(as(get("/api/v1/tasks").param("dogId", "e6-dog-a").param("includeDeleted", "true"), "ADMIN"), 501, "NOT_IMPLEMENTED");
        error(as(get("/api/v1/tasks"), "ADMIN"), 400, "VALIDATION_ERROR");
        // Global vision (MATRIU rule 1): any instructor reaches any class, dog, task, item of the club.
        error(as(put("/api/v1/class-sessions/e6-class-a/attendance").contentType("application/json").content("{\"version\":0,\"items\":[{\"bookingId\":\"b\",\"state\":\"LATE\"}]}")
                .header("Idempotency-Key", UUID.randomUUID().toString()), "INSTRUCTOR"), 400, "VALIDATION_ERROR");
        error(as(put("/api/v1/class-sessions/e6-class-a/attendance").contentType("application/json").content("{\"version\":0,\"items\":[]}")
                .header("Idempotency-Key", UUID.randomUUID().toString()), "INSTRUCTOR"), 400, "VALIDATION_ERROR");
        error(as(put("/api/v1/class-sessions/e6-class-a/attendance").contentType("application/json").content("{\"version\":0,\"items\":[{\"bookingId\":\"b\",\"state\":\"PRESENT\"}]}"),
                "INSTRUCTOR"), 400, "VALIDATION_ERROR");
        served(as(get("/api/v1/instructor/day").param("instructorId", "e6-instructor-a"), "INSTRUCTOR"), 200);
        error(as(get("/api/v1/instructor/day").param("instructorId", "e6-instructor-b"), "INSTRUCTOR"), 404, "NOT_FOUND");
        error(as(get("/api/v1/instructor/week").param("ringId", "e6-ring-b"), "ADMIN"), 404, "NOT_FOUND");
        error(as(get("/api/v1/instructor/week/export").param("format", "xlsx"), "ADMIN"), 400, "VALIDATION_ERROR");
        error(as(get("/api/v1/instructor/week/export"), "ADMIN"), 400, "VALIDATION_ERROR");
    }

    @Test void T_10_21_undeclaredFiltersAndSortsAreInvalidBeforeTheStub() throws Exception {
        for (String path : List.of("/api/v1/attendances", "/api/v1/followup")) {
            for (var entry : List.of(Map.entry("filter", "remarks:eq:x"), Map.entry("filter", "state"), Map.entry("sort", "dogName,asc"), Map.entry("size", "7"))) {
                error(as(get(path).param(entry.getKey(), entry.getValue()), "ADMIN"), 400, "INVALID_FILTER");
            }
        }
        served(as(get("/api/v1/attendances").param("filter", "classDate:between:2026-08-01,2026-08-31").param("filter", "dogId:in:e6-dog-a,e6-dog-b"), "INSTRUCTOR"), 200);
        error(as(get("/api/v1/followup").param("filter", "kind:eq:MEMBER_NOTE").param("filter", "authorAccountId:eq:e6-INSTRUCTOR").param("filter", "memberId:eq:e6-member-a")
                .param("filter", "dogId:eq:e6-dog-a").param("sort", "activityAt,desc"), "INSTRUCTOR"), 501, "NOT_IMPLEMENTED");
    }

    @Test void T_10_33_disabledTasksAnswers404BeforeTheStubOnEveryTasksRouteAndPurpose() throws Exception {
        club(CLUB, List.of(Module.WAITLIST, Module.FAQ, Module.PUSH));
        for (Route route : routes().filter(r -> r.module() != null).toList()) {
            for (String role : route.roles()) { error(call(route, CLUB, role), 404, "MODULE_DISABLED"); }
        }
        assertThat(routes().filter(r -> "TASKS".equals(r.module())).count()).isEqualTo(14);
        for (String purpose : List.of("TASK", "DOG_OBSERVATIONS")) {
            error(as(post("/api/v1/attachments/upload-url").contentType("application/json")
                    .content("{\"purpose\":\"" + purpose + "\",\"fileName\":\"a.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":4}"), "INSTRUCTOR"), 404, "MODULE_DISABLED");
            error(as(post("/api/v1/attachments").contentType("application/json").content("{\"entityType\":\"" + purpose + "\",\"entityId\":\""
                    + (purpose.equals("TASK") ? "e6-task-a" : "e6-dog-a") + "\",\"fileKey\":\"k\",\"name\":\"a.pdf\"}"), "ADMIN"), 404, "MODULE_DISABLED");
        }
        // The attendance/instructor routes stay (no module); the club «mínim» still reaches them.
        served(as(get("/api/v1/class-sessions/e6-class-a/attendance"), "INSTRUCTOR"), 200);
        served(as(get("/api/v1/me/history"), "MEMBER"), 200);
    }

    @Test void T_10_16_taskAndObservationPurposesAreStaffOnlyAndStubbedAtRegistration() throws Exception {
        for (String purpose : List.of("TASK", "DOG_OBSERVATIONS")) {
            String upload = "{\"purpose\":\"" + purpose + "\",\"fileName\":\"vídeo_balancí.mov\",\"mimeType\":\"video/quicktime\",\"sizeBytes\":" + 20L * 1024 * 1024 + "}";
            mvc.perform(as(post("/api/v1/attachments/upload-url").contentType("application/json").content(upload), "INSTRUCTOR"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.fileKey").isNotEmpty()).andExpect(jsonPath("$.uploadUrl").isNotEmpty());
            error(as(post("/api/v1/attachments/upload-url").contentType("application/json").content(upload), "MEMBER"), 403, "FORBIDDEN");
            error(as(post("/api/v1/attachments/upload-url").contentType("application/json").content(upload.replace("20971520", Long.toString(30L * 1024 * 1024))), "ADMIN"),
                    400, "FILE_TOO_LARGE").andExpect(jsonPath("$.details.maxSizeMb").value(25));
            error(as(post("/api/v1/attachments/upload-url").contentType("application/json").content(upload.replace("video/quicktime", "application/x-msdownload")), "ADMIN"),
                    400, "FILE_TYPE_NOT_ALLOWED");
            String entity = purpose.equals("TASK") ? "e6-task-a" : "e6-dog-a";
            String register = "{\"entityType\":\"" + purpose + "\",\"entityId\":\"" + entity + "\",\"fileKey\":\"k\",\"name\":\"vídeo.mov\"}";
            error(as(post("/api/v1/attachments").contentType("application/json").content(register), "INSTRUCTOR"), 501, "NOT_IMPLEMENTED");
            error(as(post("/api/v1/attachments").contentType("application/json").content(register), "MEMBER"), 403, "FORBIDDEN");
            error(as(post("/api/v1/attachments").contentType("application/json").content(register.replace(entity, "e6-missing")), "ADMIN"), 404, "NOT_FOUND");
        }
        // The S03/S07 purposes keep their E3-T03 roles: an instructor alone gets no DOG_DOCUMENT upload, staff register no member note.
        error(as(post("/api/v1/attachments/upload-url").contentType("application/json")
                .content("{\"purpose\":\"DOG_DOCUMENT\",\"fileName\":\"a.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":4}"), "INSTRUCTOR"), 403, "FORBIDDEN");
        error(as(post("/api/v1/attachments").contentType("application/json")
                .content("{\"entityType\":\"INSTRUCTOR_NOTE\",\"entityId\":\"e6-dog-a\",\"fileKey\":\"k\",\"name\":\"a.pdf\"}"), "MEMBER", "ADMIN"), 403, "FORBIDDEN");
        // Reading: DOG_OBSERVATIONS never for the member (404); removal of a member note by staff → 403.
        error(as(get("/api/v1/attachments").param("entityType", "DOG_OBSERVATIONS").param("entityId", "e6-dog-a"), "MEMBER"), 404, "NOT_FOUND");
        error(as(get("/api/v1/attachments").param("entityType", "DOG_OBSERVATIONS").param("entityId", "e6-dog-a"), "INSTRUCTOR"), 501, "NOT_IMPLEMENTED");
        error(as(get("/api/v1/attachments").param("entityType", "OTHER").param("entityId", "e6-dog-a"), "INSTRUCTOR"), 400, "VALIDATION_ERROR");
        error(as(delete("/api/v1/attachments/e6-att-note").header("Idempotency-Key", UUID.randomUUID().toString()), "ADMIN"), 403, "FORBIDDEN");
        error(as(delete("/api/v1/attachments/e6-att-note").header("Idempotency-Key", UUID.randomUUID().toString()), "MEMBER"), 501, "NOT_IMPLEMENTED");
    }

    @Test void T_10_26_T_15_13_T_15_18_noShowNoticesAndClassFinishingAreCatalogRowsWithoutAJobBeanYet() throws Exception {
        var admin = jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN");
        var jobs = mvc.perform(get("/api/v1/jobs").header("Host", HOST).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(jobs).contains("RISK_REVIEW").doesNotContain("NO_SHOW_NOTICES", "CLASS_FINISHING");
        for (String route : List.of("no-show-notices", "class-finishing")) {
            error(post("/api/v1/jobs/" + route + "/trigger").contentType("application/json").content("{\"dryRun\":true}").header("Host", HOST).with(admin), 404, "JOB_UNKNOWN");
            mvc.perform(get("/api/v1/jobs/" + route + "/runs").header("Host", HOST).with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        }
    }

    private Map<String, List<Document>> database() {
        var result = new TreeMap<String, List<Document>>();
        for (String name : mongo.getCollectionNames()) {
            if (name.equals("security_events")) { continue; }
            var documents = mongo.getCollection(name).find().sort(new Document("_id", 1)).into(new ArrayList<>());
            if (!documents.isEmpty()) { result.put(name, documents); }
        }
        return result;
    }
    @Test void T_10_21_theStubsWriteNothing() throws Exception {
        var before = database();
        for (Route route : routes().filter(r -> !r.implemented()).toList()) {
            for (String role : route.roles()) { mvc.perform(call(route, CLUB, role)).andExpect(status().isNotImplemented()); }
        }
        assertThat(database()).isEqualTo(before);
    }

    @Test void T_10_21_snapshotPublishesEveryOperationWithTypedResponsesAndListMetadata() throws Exception {
        var api = mapper.readTree(mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(routes().count()).isEqualTo(22);
        for (Route route : routes().toList()) {
            var op = api.path("paths").path(route.path()).path(route.method().toLowerCase());
            assertThat(op.isMissingNode()).as(route.path()).isFalse();
            if (route.implemented()) { assertThat(op.path("description").asText()).as(route.path()).contains("Roles:").doesNotContain("501"); }
            else { assertThat(op.path("description").asText()).as(route.path()).contains("501", "guards", "Roles:"); }
            assertThat(op.path("responses").has(Integer.toString(route.success()))).as(route.path()).isTrue();
            assertThat(op.path("operationId").asText()).as(route.path()).doesNotContain("_");
            assertThat(op.path("parameters").findValuesAsText("name")).doesNotContain("clubId");
            var key = Stream.of(op.has("parameters") ? mapper.convertValue(op.path("parameters"), JsonNode[].class) : new JsonNode[0])
                    .filter(p -> p.path("name").asText().equals("Idempotency-Key")).findFirst();
            assertThat(key.isPresent()).as(route.path() + " Idempotency-Key").isEqualTo(route.idempotency());
            if (route.idempotency()) { assertThat(key.orElseThrow().path("required").asBoolean()).as(route.path()).isTrue(); }
        }
        // Round 2: every S10 §6 route marked «I = sí», including POST /attachments of E3-T03 (optional there: CONVENCIONS_API §7 «accepten»).
        var keyed = new TreeSet<String>();
        for (Route route : routes().toList()) { if (route.idempotency()) { keyed.add(route.method() + " " + route.path()); } }
        var register = Stream.of(mapper.convertValue(api.at("/paths/~1api~1v1~1attachments/post/parameters"), JsonNode[].class))
                .filter(p -> p.path("name").asText().equals("Idempotency-Key")).findFirst().orElseThrow();
        assertThat(register.path("in").asText()).isEqualTo("header");
        assertThat(register.path("required").asBoolean()).isFalse();
        assertThat(register.at("/schema/format").asText()).isEqualTo("uuid");
        keyed.add("POST /api/v1/attachments");
        assertThat(keyed).containsExactlyInAnyOrder("PUT /api/v1/class-sessions/{id}/attendance", "PUT /api/v1/dogs/{id}/observations", "POST /api/v1/tasks",
                "DELETE /api/v1/tasks/{id}", "POST /api/v1/attachments", "DELETE /api/v1/attachments/{id}", "POST /api/v1/followup/{id}/read",
                "POST /api/v1/followup/read-all");
        assertThat(api.at("/paths/~1api~1v1~1attachments~1upload-url/post/parameters").findValuesAsText("name")).doesNotContain("Idempotency-Key");
        assertThat(strings(api.at("/paths/~1api~1v1~1attendances/get/x-filterable"))).containsExactly("dogId", "memberId", "classSessionId", "classDate", "state");
        assertThat(strings(api.at("/paths/~1api~1v1~1attendances/get/x-sortable"))).containsExactly("classStartsAt", "classDate");
        assertThat(strings(api.at("/paths/~1api~1v1~1followup/get/x-filterable"))).containsExactly("kind", "memberId", "dogId", "authorAccountId", "unread");
        assertThat(strings(api.at("/paths/~1api~1v1~1followup/get/x-sortable"))).containsExactly("activityAt");
        assertThat(api.at("/paths/~1api~1v1~1instructor~1week~1export/get/responses/200/content").has("application/pdf")).isTrue();
        // The attachment routes of E3-T03, extended with the S10 purposes.
        assertThat(strings(api.at("/components/schemas/UploadRequest/properties/purpose/enum"))).containsExactly("DOG_DOCUMENT", "DOG_PHOTO", "INSTRUCTOR_NOTE",
                "ACTIVITY_IMAGE", "ACTIVITY_DOCUMENT", "TASK", "DOG_OBSERVATIONS");
        assertThat(strings(api.at("/components/schemas/AttachmentRequest/properties/entityType/enum"))).containsExactly("INSTRUCTOR_NOTE", "TASK", "DOG_OBSERVATIONS");
        var schemas = api.at("/components/schemas");
        for (String group : List.of("instructor", "followup")) {
            var fixtures = mapper.readTree(getClass().getResourceAsStream("/fixtures/contracts/e6-" + group + "-responses.json"));
            fixtures.fields().forEachRemaining(entry -> assertFixtureSchema(schemas, schemas.path(entry.getKey().split("#")[0]), entry.getValue(), entry.getKey()));
        }
        // Module-off shapes (S10 §9): the fields a disabled module removes are optional.
        assertThat(strings(schemas.at("/AttendanceRow/required"))).doesNotContain("pendingTasksCount", "notice", "noShowNotice", "handlerName", "levelCode", "dogPhotoUrl")
                .contains("bookingId", "dogId", "state", "final");
        assertThat(strings(schemas.at("/AttendanceSheet/required"))).containsExactly("classSession", "rows", "sheet");
        assertThat(strings(schemas.at("/InstructorCard/required"))).containsExactlyInAnyOrder("dog", "member", "metrics", "lastClasses");
        assertThat(strings(schemas.at("/CardMetrics/required"))).doesNotContain("trainingsCount", "trainingsPerWeek", "attendancePct");
        for (String name : List.of("InstructorDayClass", "SheetClassSession")) { assertThat(strings(schemas.at("/" + name + "/required"))).as(name).doesNotContain("waiting"); }
        assertThat(strings(schemas.at("/WeekCell/required"))).containsExactlyInAnyOrder("date", "time", "endTime", "kind");
        assertThat(strings(schemas.at("/WeekCell/properties/kind/enum"))).containsExactly("CLASS", "TRAINING", "BLOCK");
        assertThat(strings(schemas.at("/AttendanceRow/properties/state/enum"))).containsExactly("PENDING", "PRESENT", "NOTIFIED", "NO_SHOW");
        assertThat(strings(schemas.at("/DayAttendance/properties/status/enum"))).containsExactly("NONE", "PENDING", "DONE", "CLOSED");
        assertThat(strings(schemas.at("/HistoryDetail/properties/kind/enum"))).containsExactly("BY_MEMBER", "BY_MEMBER_IN_TIME", "INSTRUCTOR_NOTICE",
                "INSTRUCTOR_NOTICE_IN_TIME", "BY_CLUB_ON_BEHALF", "SYSTEM", "BY_CLUB", "NO_SHOW");
        assertThat(schemas.path("Attachment").path("properties").has("uploadedAt")).isTrue();
        assertThat(schemas.has("AttachmentResponse")).isFalse();
        assertThat(schemas.at("/Task/properties/attachments/items/$ref").asText()).isEqualTo("#/components/schemas/Attachment");
        for (String name : List.of("StaleAttendanceDetails", "AttendanceBookingNotActiveDetails", "AttendanceWindowClosedDetails", "FileTooLargeDetails",
                "AttachmentLimitReachedDetails", "WaitlistLimitDetails")) {
            assertThat(schemas.path(name).path("properties").isEmpty()).as(name).isFalse();
        }
        for (String nullable : List.of("InstructorDayClass/ring", "AttendanceRow/notice", "AttendanceRow/noShowNotice", "InstructorCard/level", "TasksBlock/latest",
                "HistoryItem/detail", "Task/doneBy")) {
            String[] parts = nullable.split("/");
            var field = schemas.path(parts[0]).path("properties").path(parts[1]);
            assertThat(field.path("anyOf").get(0).has("$ref")).as(nullable).isTrue();
            assertThat(field.path("anyOf").get(1).path("type").asText()).as(nullable).isEqualTo("null");
        }
        // S06 forms expose the S10 attendance status (S10 §7), optional.
        for (String name : List.of("ClassSession", "DayGridCell")) {
            assertThat(strings(schemas.at("/" + name + "/properties/attendanceStatus/enum"))).as(name).containsExactly("NONE", "PENDING", "DONE", "CLOSED");
            assertThat(strings(schemas.at("/" + name + "/required"))).as(name).doesNotContain("attendanceStatus");
        }
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
        }
        if (value.isArray()) { value.forEach(item -> assertFixtureSchema(schemas, schema.path("items"), item, path + "[]")); }
        if (schema.has("enum")) { assertThat(strings(schema.path("enum"))).as(path).contains(value.asText()); }
    }
    private static List<String> strings(JsonNode node) { var values = new ArrayList<String>(); node.forEach(v -> values.add(v.asText())); return values; }
}
