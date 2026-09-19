package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class E4ContractIT extends AbstractIntegrationTest {
    static final String CLUB = "e4-club-a", OTHER = "e4-club-b", HOST = "e4-a.example.test", OTHER_HOST = "e4-b.example.test";
    static final String PUBLIC_KEY = UUID.randomUUID().toString();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired com.agilityhub.core.clubs.scheduling.application.TemplateQuery templateQuery;
    @Autowired org.springframework.data.mongodb.core.MongoTemplate mongo;
    @Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;
    record Route(String method, String path, List<String> roles, JsonNode body, Map<String, String> params,
                 boolean idempotency, int success, String module, @com.fasterxml.jackson.annotation.JsonProperty("public") boolean publicRoute,
                 boolean key, boolean impersonation) { }
    static Stream<Route> routes() throws Exception {
        try (var input = E4ContractIT.class.getResourceAsStream("/fixtures/contracts/e4-routes.json")) {
            return Arrays.stream(new ObjectMapper().readValue(input, Route[].class));
        }
    }
    @BeforeEach void prepare() {
        clock.setInstant(java.time.Instant.parse("2026-09-14T04:00:00Z"));
        mongo.remove(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").in(CLUB, OTHER)), Club.class);
        for (String clubId : List.of(CLUB, OTHER)) {
            var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(clubId, clubId.equals(CLUB) ? HOST : OTHER_HOST));
            tree.set("modules", mapper.valueToTree(Module.values())); tree.put("publicApiKeyHash", PublicClubAccess.digest(PUBLIC_KEY));
            clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(clubId); templateQuery.invalidate(clubId);
        }
        hosts.invalidate();
        for (String collection : List.of("week_templates", "weeks", "class_sessions", "ring_blocks", "activities", "activity_registrations", "levels", "rings", "instructors", "parameters")) {
            mongo.remove(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
        insert("catalogs", "Level", "e4-level-a", Map.of("name", Map.of("ca", "A"), "order", 1, "capacity", 5, "active", true));
        insert("catalogs", "Ring", "e4-ring-a", Map.of("name", "Example", "shortName", "EX", "order", 1, "active", true, "color", "#112233"));
        insert("catalogs", "Instructor", "e4-instructor-a", Map.of("memberId", "member-a", "shortName", "Example", "active", true));
        insert("scheduling", "WeekTemplate", "template-a", Map.of("name", "Stored week", "kind", "WEEKDAYS", "active", true,
                "timeBands", List.of(Map.of("id", "band-a", "startTime", "18:00", "endTime", "19:00"), Map.of("id", "band-empty", "startTime", "20:00", "endTime", "21:00")),
                "classes", List.of(Map.of("id", "template-class-a", "bandId", "band-a", "dayOfWeek", "MONDAY", "instructorIds", List.of("e4-instructor-a"), "levelIds", List.of("e4-level-a"), "capacity", 5, "capacityMode", "AUTO"))));
        insert("scheduling", "WeekTemplate", "template-saturday", Map.of("name", "Example Saturday", "kind", "SATURDAY", "active", true, "timeBands", List.of(), "classes", List.of()));
        insert("scheduling", "Week", "week-a", Map.of("isoYear", 2026, "isoWeek", 38, "startDate", "2026-09-14", "endDate", "2026-09-20", "state", "PENDING"));
        var session = new LinkedHashMap<String,Object>(); session.put("weekId","week-a");session.put("date","2026-09-14");session.put("startTime","18:00");session.put("endTime","19:00");
        session.put("startsAt","2026-09-14T16:00:00Z");session.put("endsAt","2026-09-14T17:00:00Z");session.put("state","ACTIVE");session.put("levelIds",List.of("e4-level-a"));session.put("instructorIds",List.of("e4-instructor-a"));session.put("capacity",5);session.put("capacityMode","AUTO");session.put("counters",Map.of("booked",0,"waiting",0));session.put("risk",Map.of("exempt",false,"notifiedBookingIds",List.of()));
        insert("scheduling","ClassSession","class-a",session);
        session.put("state","DRAFT");session.put("startTime","20:00");session.put("endTime","21:00");session.put("startsAt","2026-09-14T18:00:00Z");session.put("endsAt","2026-09-14T19:00:00Z");insert("scheduling","ClassSession","draft-a",session);
        insert("scheduling", "RingBlock", "block-a", Map.of("ringId", "e4-ring-a", "state", "ACTIVE", "kind", "BLOCK", "reason", "MAINTENANCE", "from", "2026-09-16T14:00:00Z", "to", "2026-09-16T15:00:00Z"));
        var activity = new LinkedHashMap<String,Object>();
        activity.put("title",Map.of("ca","Example activity"));activity.put("type","SEMINAR");activity.put("slug","example-event");activity.put("state","PUBLISHED");
        activity.put("documents",List.of(Map.of("id","document-a","name","Example","fileKey","00000000-0000-0000-0000-000000000001","mimeType","application/pdf","sizeBytes",4)));
        activity.put("location",Map.of("atClub",true));activity.put("ringIds",List.of());activity.put("levelIds",List.of());activity.put("ringBlockIds",List.of());activity.put("priceTiers",List.of());activity.put("visibility","MEMBERS");
        activity.put("date","2026-09-15");activity.put("startTime","18:00");activity.put("endTime","19:00");activity.put("registrationFrom","2026-09-01");activity.put("registrationTo","2026-09-15");
        activity.put("startsAt","2026-09-15T16:00:00Z");activity.put("endsAt","2026-09-15T17:00:00Z");activity.put("registrationOpensAt","2026-08-31T22:00:00Z");activity.put("registrationClosesAt","2026-09-15T22:00:00Z");
        activity.put("counters",Map.of("active",1,"waiting",0));activity.put("maxPlaces",1);insert("activities","Activity","activity-a",activity);
        mongo.save(new org.bson.Document("_id","member-a").append("clubId",CLUB).append("accountId","e4-MEMBER").append("status","ACTIVE").append("firstName","Example").append("bookingBlock",Map.of("active",false)),"members");
        mongo.save(new org.bson.Document("_id","e4-member-membership").append("clubId",CLUB).append("accountId","e4-MEMBER").append("memberId","member-a").append("status","ACTIVE"),"memberships");
        insert("activities", "ActivityRegistration", "registration-a", Map.of("activityId", "activity-a", "memberId", "member-a", "state", "ACTIVE", "origin", "APP", "registeredAt",clock.instant().toString(),"activityStartsAt","2026-09-15T16:00:00Z","registeredBy",Map.of("accountId","e4-MEMBER","displayName","Example")));
    }
    private void insert(String context, String name, String id, Map<String, Object> values) {
        try {
            var tree = (ObjectNode) mapper.valueToTree(values); tree.put("id", id); tree.put("clubId", CLUB); tree.put("version", 1);
            tree.put("createdAt", clock.instant().toString()); tree.put("updatedAt", clock.instant().toString());
            tree.put("createdByAccountId", "account-a"); tree.put("updatedByAccountId", "account-a");
            mongo.insert(mapper.treeToValue(tree, Class.forName("com.agilityhub.core.clubs." + context + ".persistence." + name)));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static boolean planning(Route route) {
        return route.path().startsWith("/api/v1/week-templates") || route.path().equals("/api/v1/coverage")
                || route.path().equals("/api/v1/weeks") || route.path().equals("/api/v1/weeks/{id}")
                || route.path().endsWith("/generation-candidates") || route.path().endsWith("/generation");
    }
    private static boolean activityRoute(Route route) { return route.path().contains("activit"); }
    private static boolean implemented(Route route) {
        if (activityRoute(route)) return true;
        return planning(route) || route.path().startsWith("/api/v1/class-sessions") || route.path().startsWith("/api/v1/ring-blocks")
                || route.path().equals("/api/v1/day-grid") || route.path().endsWith("/calendar") || route.path().equals("/api/v1/weeks/{id}/validation");
    }
    private String path(Route r, String clubId) {
        String id = r.path().contains("week-templates") ? "template-a" : r.path().contains("weeks/") ? "week-a"
                : r.path().contains("class-sessions/") ? "class-a" : r.path().contains("ring-blocks/") ? "block-a"
                : r.path().contains("activity-registrations/") ? "registration-a" : "activity-a";
        return r.path().replace("{id}", id).replace("{bandId}", "band-a").replace("{classId}", "template-class-a")
                .replace("{activityId}", "activity-a").replace("{docId}", "document-a").replace("{clubSlug}", clubId)
                .replace("{slug}", "example-event").replace("{fileId}", "document-a");
    }
    private MockHttpServletRequestBuilder call(Route r, String clubId, String role) throws Exception {
        var request = request(HttpMethod.valueOf(r.method()), (r.method().equals("DELETE") && r.path().contains("/bands/") ? path(r, clubId).replace("band-a", "band-empty") : path(r, clubId))).header("Host", r.publicRoute() ? "core.example.test" : clubId.equals(CLUB) ? HOST : OTHER_HOST);
        if (r.body() != null && !r.body().isNull()) { request.contentType("application/json").content(mapper.writeValueAsString(r.body()).replace("level-a", "e4-level-a").replace("ring-a", "e4-ring-a").replace("instructor-a", "e4-instructor-a")); }
        if (r.method().equals("POST") && r.path().endsWith("/bands")) { request.content("{\"startTime\":\"19:00\",\"endTime\":\"20:00\"}"); }
        if (planning(r) && r.method().equals("PATCH")) {
            var body = (ObjectNode) r.body().deepCopy();
            body.put("version", mongo.findById("template-a", com.agilityhub.core.clubs.scheduling.persistence.WeekTemplate.class).version());
            request.content(mapper.writeValueAsString(body));
        }
        if(r.method().equals("PATCH") && (r.path().startsWith("/api/v1/class-sessions") || r.path().startsWith("/api/v1/ring-blocks"))) {
            var body=(ObjectNode)r.body().deepCopy();boolean session=r.path().contains("class-sessions");
            body.put("version",((Number)mongo.findById(session?"class-a":"block-a",org.bson.Document.class,session?"class_sessions":"ring_blocks").get("version")).longValue());request.content(mapper.writeValueAsString(body));
        }
        r.params().forEach(request::param);
        if (r.idempotency()) { request.header("Idempotency-Key", UUID.randomUUID().toString()); }
        if (r.key()) { request.header("X-Api-Key", PUBLIC_KEY); }
        if (!role.equals("ANON")) { request.with(jwt().jwt(j -> j.subject("e4-" + role).claim("clubId", clubId).claim("memberId", "member-a"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role))); }
        return request;
    }
    private ResultActions error(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        var result = mvc.perform(request);
        String label = result.andReturn().getRequest().getMethod() + " " + result.andReturn().getRequest().getRequestURI();
        assertThat(result.andReturn().getResponse().getStatus()).as(label).isEqualTo(status);
        assertThat(mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("code").asText()).as(label).isEqualTo(code);
        return result.andExpect(jsonPath("$.traceId").isNotEmpty()).andExpect(jsonPath("$.message").isNotEmpty());
    }
    @ParameterizedTest @MethodSource("routes")
    void T_06_21_T_07_19_everyRouteEnforcesAllRolesAndTenantResourceIsolation(Route route) throws Exception {
        for (String role : List.of("ANON", "MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN")) {
            prepare();
            boolean allowed = route.roles().contains(role);
            if (allowed && implemented(route)) {
                var result = mvc.perform(call(route, CLUB, role)).andReturn().getResponse();
                if(activityRoute(route)) assertThat(result.getStatus()).as(route.method()+" "+route.path()+": "+result.getContentAsString()).isBetween(200,499).isNotIn(401,403);
                else assertThat(result.getStatus()).as(route.method() + " " + route.path() + ": " + result.getContentAsString()).isBetween(200, 299);
            } else {
                error(call(route, CLUB, role), allowed ? 501 : role.equals("ANON") ? 401 : 403,
                        allowed ? "NOT_IMPLEMENTED" : role.equals("ANON") ? "UNAUTHENTICATED" : "FORBIDDEN");
            }
        }
        if (!route.publicRoute()) {
            String role = route.roles().getFirst();
            error(call(route, CLUB, role).with(req -> { req.removeHeader("Host"); req.addHeader("Host", OTHER_HOST); return req; }), 403, "TENANT_MISMATCH");
            if (route.path().contains("{")) { error(call(route, OTHER, role), 404, "NOT_FOUND"); }
            error(call(route, CLUB, role).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))), 403, "NO_MEMBERSHIP");
        }
        if (route.publicRoute() && route.path().contains("{slug}")) { error(call(route, OTHER, "ANON"), 404, "NOT_FOUND"); }
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_06_21_T_07_19_validImpersonationIsRestrictedToTheExplicitMemberRoutes() throws Exception {
        for (String kind : List.of("admin", "member")) {
            String id = "e4-imp-" + kind;
            var role = kind.equals("admin") ? com.agilityhub.core.identity.domain.Role.ADMIN : com.agilityhub.core.identity.domain.Role.MEMBER;
            mongo.save(new com.agilityhub.core.identity.persistence.Account(id, id + "@example.test", "Example " + kind, "en", null,
                    Set.of(), com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
            mongo.save(new com.agilityhub.core.identity.persistence.Membership(id, id, CLUB, "member-a", Set.of(role),
                    com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, role));
        }
        mongo.save(new org.bson.Document("_id", "member-a").append("clubId", CLUB).append("accountId", "e4-imp-member").append("status", "ACTIVE"), "members");
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try (var scope = TenantContext.open(CLUB)) { issued = impersonations.create("e4-imp-admin", "member-a", "Contract authorization test"); }
        for (Route route : routes().toList()) {
            if(route.impersonation() && implemented(route)) {
                var result=mvc.perform(call(route,CLUB,"MEMBER").with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER"))).andReturn().getResponse();
                if(activityRoute(route)) assertThat(result.getStatus()).as(result.getContentAsString()).isBetween(200,499).isNotIn(401,403);
                else assertThat(result.getStatus()).isEqualTo(200); continue;
            }
            error(call(route, CLUB, "MEMBER").with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER")),
                    route.impersonation() ? 501 : 403, route.impersonation() ? "NOT_IMPLEMENTED" : route.publicRoute() ? "FORBIDDEN" : "IMPERSONATION_DENIED");
        }
    }
    @Test void T_06_21_T_07_10_memberViewsAndOwnRegistrationsRejectStaffAndOtherMembers() throws Exception {
        error(get("/api/v1/day-grid").param("date", "2026-09-14").param("view", "instructor").header("Host", HOST)
                .with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_MEMBER")), 403, "FORBIDDEN");
        for (String role : List.of("ADMIN", "INSTRUCTOR")) {
            mvc.perform(get("/api/v1/day-grid").param("date","2026-09-14").param("view","instructor").header("Host",HOST)
                    .with(jwt().jwt(j -> j.claim("clubId",CLUB)).authorities(() -> "ROLE_"+role))).andExpect(status().isOk());
            for (Route route : routes().filter(r -> r.roles().equals(List.of("MEMBER"))).toList()) {
                error(call(route, CLUB, role).with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_MEMBER", () -> "ROLE_" + role)), 403, "FORBIDDEN");
            }
        }
        for (Route route : routes().filter(r -> r.path().startsWith("/api/v1/activity-registrations/{id}")).toList()) {
            error(call(route, CLUB, "MEMBER").with(jwt().jwt(j -> j.claim("clubId", CLUB).claim("memberId", "someone-else")).authorities(() -> "ROLE_MEMBER")), 404, "NOT_FOUND");
        }
    }
    @Test void T_07_20_disabledActivitiesGuardsEveryClubAndPublicRouteBeforeTheStub() throws Exception {
        var tree = (ObjectNode) mapper.valueToTree(clubs.findById(CLUB).orElseThrow()); tree.set("modules", mapper.createArrayNode());
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(CLUB);
        for (Route route : routes().filter(r -> r.module() != null).toList()) {
            for (String role : route.roles()) { error(call(route, CLUB, role), 404, "MODULE_DISABLED"); }
            if (route.body() != null && !route.body().isNull()) {
                error(call(route, CLUB, route.roles().getFirst()).content("{"), 404, "MODULE_DISABLED");
            }
        }
        error(get("/api/v1/activity-registrations/export").param("format", "xlsx").header("Host", HOST)
                .with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN")), 404, "MODULE_DISABLED");
        for (String purpose : List.of("ACTIVITY_IMAGE", "ACTIVITY_DOCUMENT")) {
            error(post("/api/v1/attachments/upload-url").contentType("application/json").content(mapper.writeValueAsString(Map.of("purpose", purpose, "fileName", "Example.png", "mimeType", "image/png", "sizeBytes", 4)))
                    .header("Host", HOST).with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN")), 404, "MODULE_DISABLED");
        }
    }
    @Test void T_06_20_T_07_17_everyE4RouteHasAnImplementedHandler() throws Exception {
        assertThat(routes().filter(r -> !implemented(r)).toList()).isEmpty();
        var result=mvc.perform(call(routes().filter(r -> r.method().equals("GET") && r.path().equals("/api/v1/activities")).findFirst().orElseThrow(),CLUB,"ADMIN")).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("items")).hasSize(1);
    }
    private Map<String, List<org.bson.Document>> database() {
        var result = new TreeMap<String, List<org.bson.Document>>();
        for (String name : mongo.getCollectionNames()) {
            var documents = mongo.getCollection(name).find().sort(new org.bson.Document("_id", 1)).into(new ArrayList<>());
            if (!documents.isEmpty()) { result.put(name, documents); }
        }
        return result;
    }
    @Test void T_06_13_T_07_17_requestBoundariesRejectMutableDatesBadWeeksKeysAndWaitlistOff() throws Exception {
        var patch = routes().filter(r -> r.path().equals("/api/v1/class-sessions/{id}") && r.method().equals("PATCH")).findFirst().orElseThrow();
        error(call(patch, CLUB, "ADMIN").content("{\"version\":1,\"date\":\"2026-09-15\"}"), 400, "VALIDATION_ERROR");
        var week = routes().filter(r -> r.path().equals("/api/v1/weeks") && r.method().equals("POST")).findFirst().orElseThrow();
        error(call(week, CLUB, "ADMIN").content("{\"startDate\":\"2026-09-15\"}"), 400, "VALIDATION_ERROR");
        for (String suffix : List.of("", "/example-event")) {
            error(get("/api/v1/public/" + CLUB + "/activities" + suffix).header("Host", "core.example.test"), 403, "INVALID_API_KEY");
            error(get("/api/v1/public/" + CLUB + "/activities" + suffix).header("X-Api-Key", "invalid"), 403, "INVALID_API_KEY");
        }
        error(get("/api/v1/public/missing-club/activities"), 404, "CLUB_NOT_FOUND");
        var tree = (ObjectNode) mapper.valueToTree(clubs.findById(CLUB).orElseThrow()); tree.set("modules", mapper.valueToTree(List.of(Module.ACTIVITIES)));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(CLUB);
        var register = routes().filter(r -> r.path().equals("/api/v1/activity-registrations")).findFirst().orElseThrow();
        mongo.remove(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is("registration-a")),"activity_registrations");
        error(call(register, CLUB, "MEMBER").content("{\"activityId\":\"activity-a\",\"joinWaitlist\":true}"), 409, "ACTIVITY_FULL");
    }
    @Test void T_07_17_publicFilesCheckPublicationAndTheExactActivityFileWithoutAKey() throws Exception {
        String base = "/api/v1/public/" + CLUB + "/activities/example-event";
        error(get(base + "/files/missing"), 404, "NOT_FOUND");
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is("activity-a")),
                new org.springframework.data.mongodb.core.query.Update().set("image", new org.bson.Document("fileId", "image-a").append("name", "Example image").append("fileKey","00000000-0000-0000-0000-000000000002").append("mimeType","image/png").append("sizeBytes", 4)), "activities");
        mvc.perform(get(base+"/files/image-a")).andExpect(status().isFound()).andExpect(header().exists("Location"));
        mvc.perform(get(base+"/files/document-a")).andExpect(status().isFound());
        error(get(base + "/files/missing"), 404, "NOT_FOUND");
        for (String state : List.of("DRAFT", "CANCELLED", "FINISHED")) {
            mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is("activity-a")),
                    new org.springframework.data.mongodb.core.query.Update().set("state", state), "activities");
            if(state.equals("DRAFT")) error(get(base+"/files/image-a"),404,"NOT_FOUND"); else mvc.perform(get(base+"/files/image-a")).andExpect(status().isFound());
        }
    }
    @Test void T_06_20_nestedAndQueryResourceGuardsRejectMissingTargetsAndAcceptBothCoverageScopes() throws Exception {
        for (String path : List.of("/week-templates/template-a/bands/missing", "/week-templates/template-a/classes/missing", "/activities/activity-a/documents/missing")) {
            error(delete("/api/v1" + path).header("Host", HOST).with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN")), 404, "NOT_FOUND");
        }
        for (Map<String, String> params : List.of(Map.<String, String>of(), Map.of("weekId", "week-a", "templateId", "template-a"), Map.of("weekId", "week-a", "saturdayTemplateId", "template-saturday"))) {
            var request = get("/api/v1/coverage").header("Host", HOST).with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN"));
            params.forEach(request::param); error(request, 400, "VALIDATION_ERROR");
        }
        for (String field : List.of("weekId", "templateId")) {
            mvc.perform(get("/api/v1/coverage").param(field, field.equals("weekId") ? "week-a" : "template-a").header("Host", HOST)
                    .with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN"))).andExpect(status().isOk());
        }
        var generation = routes().filter(r -> r.path().endsWith("/generation")).findFirst().orElseThrow();
        mvc.perform(call(generation, CLUB, "ADMIN").content("{\"weekdayTemplateId\":\"template-a\",\"saturdayTemplateId\":\"template-saturday\"}")).andExpect(status().isOk());
        var template = routes().filter(r -> r.path().equals("/api/v1/week-templates") && r.method().equals("POST")).findFirst().orElseThrow();
        mvc.perform(call(template, CLUB, "ADMIN").content("{\"name\":\"Copied\",\"kind\":\"WEEKDAYS\",\"copyFromId\":\"template-a\"}")).andExpect(status().isCreated());
        error(get("/api/v1/day-grid").param("date", "2026-09-14").param("view", "invalid").header("Host", HOST)
                .with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN")), 400, "VALIDATION_ERROR");
    }
    @Test void T_06_20_T_07_17_snapshotPublishesEveryOperationWithTypedResponsesAndListMetadata() throws Exception {
        var api = mapper.readTree(mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(routes().count()).isEqualTo(55);
        for (Route route : routes().toList()) {
            var op = api.path("paths").path(route.path()).path(route.method().toLowerCase());
            assertThat(op.isMissingNode()).as(route.path()).isFalse();
            if (implemented(route)) { assertThat(op.path("description").asText()).doesNotContain("501"); }
            else { assertThat(op.path("description").asText()).contains("501", "guards"); }
            assertThat(op.path("responses").has(Integer.toString(route.success()))).as(route.path()).isTrue();
            assertThat(op.path("parameters").findValuesAsText("name")).doesNotContain("clubId");
            if (route.idempotency()) {
                var key = Stream.of(mapper.convertValue(op.path("parameters"), JsonNode[].class)).filter(p -> p.path("name").asText().equals("Idempotency-Key")).findFirst().orElseThrow();
                assertThat(key.path("required").asBoolean()).isTrue();
            }
            if (route.key()) {
                assertThat(op.at("/security/0/clubApiKey").isArray()).isTrue();
                assertThat(op.at("/responses/200/headers/Cache-Control/schema/example").asText()).isEqualTo("public, max-age=300");
                assertThat(op.at("/responses/200/headers/ETag").isMissingNode()).isFalse();
            }
        }
        for (var entry : Map.of("weeks", "startDate,state", "class-sessions", "date,state,ringId,instructorId,levelId,weekId",
                "ring-blocks", "ringId,kind,reason,state,from,to", "activities", "state,type,date,ringId,levelId,deleted,registrationOpen",
                "activity-registrations/export", "activityId,state,origin,registeredAt,memberId").entrySet()) {
            assertThat(strings(api.path("paths").path("/api/v1/" + entry.getKey()).at("/get/x-filterable"))).containsExactly(entry.getValue().split(","));
        }
        var schemas = api.at("/components/schemas");
        for (String name : List.of("WeekTemplate", "Week", "WeekCalendar", "Coverage", "DayGrid", "ClassSession", "RingBlock", "Activity", "ActivityRegistration", "MemberActivityDetail", "PublicActivity")) {
            assertThat(schemas.path(name).path("properties").isEmpty()).as(name).isFalse();
        }
        for (String group : List.of("scheduling", "activities")) {
            var fixtures = mapper.readTree(getClass().getResourceAsStream("/fixtures/contracts/e4-" + group + "-responses.json"));
            fixtures.fields().forEachRemaining(entry -> assertFixtureSchema(schemas,
                    schemas.path(entry.getKey().equals("ValidationResult") ? "WeekValidationResult" : entry.getKey()), entry.getValue(), entry.getKey()));
        }
        assertPublicSchema(schemas, schemas.path("PublicActivity"), new HashSet<>());
        for (String nullable : List.of("Activity/image", "Activity/cancellation", "Activity/ringBlockWindow", "ClassSession/cancellation", "ClassSession/origin", "MemberActivityDetail/myRegistration")) {
            String[] parts = nullable.split("/");
            var field = schemas.path(parts[0]).path("properties").path(parts[1]);
            assertThat(field.path("anyOf").get(0).has("$ref")).isTrue();
            assertThat(field.path("anyOf").get(1).path("type").asText()).isEqualTo("null");
        }
        assertThat(schemas.at("/Activity/properties/priceTiers/maxItems").asInt(-1)).isZero();
        assertThat(schemas.at("/ClassSessionPatchRequest/properties").has("date")).isFalse();
        assertThat(strings(schemas.at("/ActivityPatchRequest/required"))).containsExactly("version");
        assertThat(strings(schemas.at("/UploadRequest/properties/purpose/enum"))).contains("ACTIVITY_IMAGE", "ACTIVITY_DOCUMENT");
        assertThat(schemas.at("/ValidationResult/properties").has("memberId")).isTrue();
        assertThat(schemas.at("/WeekValidationResult/properties").has("validatedClassIds")).isTrue();
    }
    private void assertFixtureSchema(JsonNode schemas, JsonNode schema, JsonNode value, String path) {
        if (schema.has("anyOf")) {
            assertFixtureSchema(schemas, schema.path("anyOf").get(0), value, path); return;
        }
        if (schema.has("$ref")) {
            assertFixtureSchema(schemas, schemas.path(schema.path("$ref").asText().substring("#/components/schemas/".length())), value, path); return;
        }
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
    private void assertPublicSchema(JsonNode schemas, JsonNode schema, Set<String> visited) {
        if (schema.has("$ref")) {
            String name = schema.path("$ref").asText().substring("#/components/schemas/".length());
            if (visited.add(name)) { assertPublicSchema(schemas, schemas.path(name), visited); }
        }
        schema.path("properties").fields().forEachRemaining(field -> {
            assertThat(field.getKey()).doesNotStartWith("member").doesNotStartWith("phone").isNotIn("registrations", "registeredBy", "fullName", "emails", "internalNotes", "createdByName");
            assertPublicSchema(schemas, field.getValue(), visited);
        });
        if (schema.has("items")) { assertPublicSchema(schemas, schema.path("items"), visited); }
    }
    private static List<String> strings(JsonNode node) { var values = new ArrayList<String>(); node.forEach(v -> values.add(v.asText())); return values; }
}
