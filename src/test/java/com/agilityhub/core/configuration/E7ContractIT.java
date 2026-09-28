package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.messaging.domain.*;
import com.agilityhub.core.clubs.messaging.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.LocalizedText;
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
 * E7-T01 contract (S11 WP-11-A): every S11 §6 route is published with typed forms and answers 501 NOT_IMPLEMENTED behind its
 * role, tenant, impersonation, module and resource guards (T-11-28 role matrix, T-11-29 cross-tenant 404); the notification
 * log validates its universal-list query first (T-11-26), PUSH off hides the devices and FAQ off the FAQ (T-11-21, R-11-17),
 * and no stub writes anything. E7-T02 implements `POST /email-unsubscribes`: behind the same guards it rejects the fixture's
 * token with 422 (its behaviour is T-11-23, `EmailUnsubscribeIT`).
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class E7ContractIT extends AbstractIntegrationTest {
    static final String CLUB = "e7-club-a", OTHER = "e7-club-b", HOST = "e7-a.example.test", OTHER_HOST = "e7-b.example.test";
    static final List<String> ROLES = List.of("ANON", "MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN");
    static final List<String> DATA = List.of("message_templates", "notifications", "push_subscriptions", "members", "memberships", "faq_entries", "domain_events",
            "audit_entries", "idempotency_records");
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
        /** E7-T02 implements the unsubscribe link: the fixture's token is not valid → 422, never the stub's 501. */
        boolean implemented() { return label().equals("POST /api/v1/email-unsubscribes"); }
        int allowedStatus() { return implemented() ? 422 : 501; }
        String allowedCode() { return implemented() ? "UNSUBSCRIBE_TOKEN_INVALID" : "NOT_IMPLEMENTED"; }
        String label() { return method + " " + path; }
    }
    static Stream<Route> routes() throws Exception {
        try (var input = E7ContractIT.class.getResourceAsStream("/fixtures/contracts/e7-routes.json")) {
            return Arrays.stream(new ObjectMapper().readValue(input, Route[].class));
        }
    }

    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2026-09-28T07:00:00Z"));
        for (String clubId : List.of(CLUB, OTHER)) { club(clubId, List.of(Module.values())); }
        hosts.invalidate();
        for (String collection : DATA) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        Instant now = clock.instant();
        mongo.save(new Document("_id", "e7-member-a").append("clubId", CLUB).append("accountId", "e7-MEMBER").append("status", "ACTIVE").append("firstName", "Example")
                .append("lastName1", "Example").append("bookingBlock", Map.of("active", false)).append("version", 0), "members");
        var title = new LocalizedText(Map.of("ca", "Reserva confirmada"), "ca");
        mongo.insert(new MessageTemplate("e7-template-a", CLUB, "N-04", TemplateKind.CATALOG, NotificationCategory.OPERATIONAL, title, title, null, TemplateIcon.check,
                TemplateColor.OK, NotificationCatalog.byCode("N-04").orElseThrow().defaultMatrix(), true, false, false, TemplateStatus.ACTIVE, 0L, now, "seed", now, "seed"));
        mongo.insert(new Notification("e7-notification-a", CLUB, "e7-MEMBER", "N-04", "APP", Notification.Status.SENT, null, now, null, null, "ca", now, null));
        for (String owner : List.of("MEMBER", "INSTRUCTOR", "ADMIN")) {
            mongo.insert(new Notification("e7-own-" + owner, CLUB, "e7-" + owner, "N-04", "APP", Notification.Status.SENT, null, now, null, null, "ca", now, null));
            mongo.insert(new PushSubscription("e7-sub-" + owner, CLUB, "e7-" + owner, "https://push.example.test/send/" + owner, null,
                    new PushSubscription.Keys("p256dh", "auth"), "iPhone · Safari", "UA", PushSubscription.Status.ACTIVE, 0, null, null, 0L, now, "e7-" + owner, now, "e7-" + owner));
        }
        mongo.insert(new Notification("e7-own-imp", CLUB, "e7-imp-member", "N-04", "APP", Notification.Status.SENT, null, now, null, null, "ca", now, null));
    }
    private void club(String clubId, List<Module> modules) {
        mongo.remove(Query.query(Criteria.where("_id").is(clubId)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(clubId, clubId.equals(CLUB) ? HOST : OTHER_HOST));
        tree.set("modules", mapper.valueToTree(modules));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(clubId);
    }

    /** `owner` picks the caller's own notification or device (`e7-own-{owner}`, `e7-sub-{owner}`); `imp` is the impersonated member's. */
    private String path(Route r, String owner) {
        String id = r.path().startsWith("/api/v1/message-templates") ? "e7-template-a" : r.path().startsWith("/api/v1/notifications/") ? "e7-notification-a"
                : r.path().startsWith("/api/v1/me/notifications/") ? "e7-own-" + owner : r.path().startsWith("/api/v1/members/") ? "e7-member-a" : "e7-sub-" + owner;
        return r.path().replace("{id}", id);
    }
    private MockHttpServletRequestBuilder call(Route r, String clubId, String role) throws Exception { return call(r, clubId, role, role); }
    private MockHttpServletRequestBuilder call(Route r, String clubId, String role, String owner) throws Exception {
        var request = request(HttpMethod.valueOf(r.method()), path(r, owner)).header("Host", clubId.equals(CLUB) ? HOST : OTHER_HOST);
        if (r.body() != null && !r.body().isNull()) { request.contentType("application/json").content(mapper.writeValueAsString(r.body())); }
        r.params().forEach(request::param);
        if (r.idempotency()) { request.header("Idempotency-Key", UUID.randomUUID().toString()); }
        if (!role.equals("ANON")) {
            request.with(jwt().jwt(j -> j.subject("e7-" + role).claim("clubId", clubId).claim("memberId", "e7-member-a"))
                    .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
        }
        return request;
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role) {
        return request.header("Host", HOST).with(jwt().jwt(j -> j.subject("e7-" + role).claim("clubId", CLUB).claim("memberId", "e7-member-a"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    private ResultActions error(MockHttpServletRequestBuilder request, int status, String... codes) throws Exception {
        var result = mvc.perform(request);
        String label = result.andReturn().getRequest().getMethod() + " " + result.andReturn().getRequest().getRequestURI();
        assertThat(result.andReturn().getResponse().getStatus()).as(label + " " + result.andReturn().getResponse().getContentAsString()).isEqualTo(status);
        assertThat(mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("code").asText()).as(label).isIn((Object[]) codes);
        return result.andExpect(jsonPath("$.traceId").isNotEmpty()).andExpect(jsonPath("$.message").isNotEmpty());
    }

    @ParameterizedTest @MethodSource("routes")
    void T_11_28_T_11_29_everyRouteEnforcesRolesTenantAndResourceIsolationBefore501(Route route) throws Exception {
        for (String role : ROLES) {
            boolean allowed = route.roles().contains(role);
            error(call(route, CLUB, role), allowed ? route.allowedStatus() : role.equals("ANON") ? 401 : 403,
                    allowed ? route.allowedCode() : role.equals("ANON") ? "UNAUTHENTICATED" : "FORBIDDEN");
        }
        if (route.club()) {
            String role = route.roles().getFirst();
            error(call(route, CLUB, role).with(req -> { req.removeHeader("Host"); req.addHeader("Host", OTHER_HOST); return req; }), 403, "TENANT_MISMATCH");
            error(call(route, CLUB, role).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))), 403, "NO_MEMBERSHIP");
            // T-11-29: nothing of club A is visible or changeable from club B.
            if (route.resource()) { error(call(route, OTHER, role), 404, "NOT_FOUND"); }
        } else {
            error(call(route, CLUB, "ANON").with(req -> { req.removeHeader("Host"); req.addHeader("Host", "unknown.example.test"); return req; }), 404, "UNKNOWN_HOST");
        }
        assertThat(TenantContext.current()).isNull();
    }

    @Test void T_11_28_impersonationReadsAndMarksTheFeedAndSavesPreferencesButNeverReachesTheRest() throws Exception {
        var issued = impersonate();
        for (Route route : routes().toList()) {
            var request = call(route, CLUB, "MEMBER", "imp").with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER"));
            if (route.impersonation()) { error(request, 501, "NOT_IMPLEMENTED"); }
            else { error(request, 403, "IMPERSONATION_DENIED", "FORBIDDEN"); }
        }
        assertThat(routes().filter(Route::impersonation).map(Route::label).toList()).containsExactly("GET /api/v1/me/notifications",
                "POST /api/v1/me/notifications/{id}/read", "POST /api/v1/me/notifications/read-all", "GET /api/v1/me/notification-preferences",
                "PUT /api/v1/me/notification-preferences");
        // The feed of the impersonated member only: the admin's own notification is not theirs → 404.
        error(post("/api/v1/me/notifications/e7-own-ADMIN/read").header("Host", HOST).with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER")), 404, "NOT_FOUND");
    }
    private com.agilityhub.core.identity.application.ImpersonationService.Issued impersonate() {
        for (String kind : List.of("admin", "member")) {
            String id = "e7-imp-" + kind;
            var role = kind.equals("admin") ? com.agilityhub.core.identity.domain.Role.ADMIN : com.agilityhub.core.identity.domain.Role.MEMBER;
            mongo.save(new com.agilityhub.core.identity.persistence.Account(id, id + "@example.test", "Example " + kind, "en", null,
                    Set.of(), com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
            mongo.save(new com.agilityhub.core.identity.persistence.Membership(id, id, CLUB, "e7-member-a", Set.of(role),
                    com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, role));
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is("e7-member-a")), new org.springframework.data.mongodb.core.query.Update().set("accountId", "e7-imp-member"), "members");
        try (var scope = TenantContext.open(CLUB)) { return impersonations.create("e7-imp-admin", "e7-member-a", "Contract authorization test"); }
    }

    @Test void T_11_19_T_11_21_T_11_29_anotherAccountsNotificationOrDeviceIsNotFound() throws Exception {
        error(as(post("/api/v1/me/notifications/e7-own-ADMIN/read"), "MEMBER"), 404, "NOT_FOUND");
        error(as(post("/api/v1/me/notifications/e7-own-MEMBER/read"), "INSTRUCTOR"), 404, "NOT_FOUND");
        error(as(post("/api/v1/me/notifications/e7-own-MEMBER/read"), "MEMBER"), 501, "NOT_IMPLEMENTED");
        error(as(delete("/api/v1/push-subscriptions/e7-sub-ADMIN"), "MEMBER"), 404, "NOT_FOUND");
        error(as(delete("/api/v1/push-subscriptions/e7-sub-MEMBER"), "MEMBER"), 501, "NOT_IMPLEMENTED");
        error(as(get("/api/v1/message-templates/e7-missing"), "ADMIN"), 404, "NOT_FOUND");
        error(as(get("/api/v1/notifications/e7-missing"), "ADMIN"), 404, "NOT_FOUND");
        error(as(put("/api/v1/members/e7-missing/notification-preferences").contentType("application/json").content("{}"), "ADMIN"), 404, "NOT_FOUND");
        // Bodies and parameters are validated before the stub.
        error(as(post("/api/v1/message-templates").contentType("application/json").content("{\"category\":\"CLUB_NEWS\"}"), "ADMIN"), 400, "VALIDATION_ERROR");
        error(as(post("/api/v1/message-templates/e7-template-a/send").contentType("application/json").content("{\"recipients\":{},\"dryRun\":true}"), "ADMIN"),
                400, "VALIDATION_ERROR");
        error(as(put("/api/v1/me/notification-preferences").contentType("application/json").content("{\"reminderMinutesBefore\":\"soon\"}"), "MEMBER"), 400, "VALIDATION_ERROR");
        error(as(post("/api/v1/push-subscriptions").contentType("application/json").content("{\"endpoint\":\"https://push.example.test/x\"}"), "MEMBER"), 400, "VALIDATION_ERROR");
        error(as(get("/api/v1/me/notifications").param("audience", "EVERYONE"), "MEMBER"), 400, "VALIDATION_ERROR");
        error(post("/api/v1/email-unsubscribes").header("Host", HOST).contentType("application/json").content("{\"token\":\"\"}"), 400, "VALIDATION_ERROR");
    }

    @Test void T_11_26_theLogAndItsExportValidateTheUniversalListQueryBeforeTheStub() throws Exception {
        for (String path : List.of("/api/v1/notifications", "/api/v1/notifications/export")) {
            for (var entry : List.of(Map.entry("filter", "title:eq:x"), Map.entry("filter", "code"), Map.entry("sort", "code,asc"), Map.entry("size", "7"),
                    Map.entry("fields", "body"))) {
                error(as(get(path).param("format", "xlsx").param(entry.getKey(), entry.getValue()), "ADMIN"), 400, "INVALID_FILTER");
            }
            error(as(get(path).param("format", "xlsx").param("filter", "code:eq:N-08a").param("filter", "channel:in:SMS,EMAIL").param("filter", "status:eq:FAILED")
                    .param("filter", "memberId:eq:e7-member-a").param("filter", "category:eq:CLUB_CHANGES")
                    .param("filter", "createdAt:between:2026-09-01T00:00:00Z,2026-09-30T23:59:59Z").param("sort", "createdAt,desc")
                    .param("fields", "id,code,channels").param("size", "200"), "ADMIN"), 501, "NOT_IMPLEMENTED");
        }
        error(as(get("/api/v1/notifications/export").param("format", "xlsx").param("columns", "body"), "ADMIN"), 400, "INVALID_FILTER");
        error(as(get("/api/v1/notifications/export").param("format", "xlsx").param("columns", "code,readAt"), "ADMIN"), 501, "NOT_IMPLEMENTED");
        error(as(get("/api/v1/notifications/filter-values").param("field", "title"), "ADMIN"), 400, "INVALID_FILTER");
        error(as(get("/api/v1/notifications/filter-values").param("field", "channel").param("filter", "body:eq:x"), "ADMIN"), 400, "INVALID_FILTER");
        error(as(get("/api/v1/notifications/filter-values").param("field", "channel").param("filter", "code:eq:N-15"), "ADMIN"), 501, "NOT_IMPLEMENTED");
    }

    @Test void T_11_21_T_11_27_pushOffHidesTheDevicesAndFaqOffHidesTheFaq() throws Exception {
        var modules = new ArrayList<>(List.of(Module.values())); modules.removeAll(List.of(Module.PUSH, Module.FAQ));
        club(CLUB, modules);
        for (Route route : routes().filter(r -> "PUSH".equals(r.module())).toList()) {
            for (String role : route.roles()) { error(call(route, CLUB, role), 404, "MODULE_DISABLED"); }
        }
        assertThat(routes().filter(r -> "PUSH".equals(r.module())).map(Route::label)).containsExactly("POST /api/v1/push-subscriptions", "DELETE /api/v1/push-subscriptions/{id}");
        for (String role : List.of("MEMBER", "INSTRUCTOR", "ADMIN")) { error(as(get("/api/v1/faq-entries"), role), 404, "MODULE_DISABLED"); }
        // The feed, the preferences and the templates have no module.
        error(as(get("/api/v1/me/notifications"), "MEMBER"), 501, "NOT_IMPLEMENTED");
        error(as(get("/api/v1/me/notification-preferences"), "MEMBER"), 501, "NOT_IMPLEMENTED");
        error(as(get("/api/v1/message-templates"), "ADMIN"), 501, "NOT_IMPLEMENTED");
        club(CLUB, List.of(Module.values()));
        mvc.perform(as(get("/api/v1/faq-entries"), "MEMBER")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
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
    @Test void WP_11_A_theStubsWriteNothing() throws Exception {
        var before = database();
        for (Route route : routes().toList()) {
            // The implemented unsubscribe route rejects the fixture's token without writing either.
            for (String role : route.roles()) { mvc.perform(call(route, CLUB, role)).andExpect(status().is(route.allowedStatus())); }
        }
        assertThat(database()).isEqualTo(before);
    }

    /**
     * Round 2 (review #1): every operation publishes the authentication it enforces, in the live document and in the committed
     * snapshot. The effective requirement is the operation's `security`, else the document's: `[]` for the anonymous
     * operation, the bearer for the rest; and a call without a token answers 401 exactly on the bearer ones.
     */
    @Test void WP_11_A_everyOperationPublishesTheAuthenticationItEnforces() throws Exception {
        var live = mapper.readTree(mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var committed = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("docs/openapi/openapi.json")));
        var bearer = mapper.readTree("[{\"bearer\": []}]");
        for (var api : List.of(live, committed)) {
            assertThat(api.path("security")).isEqualTo(bearer);
            for (Route route : routes().toList()) {
                var op = api.path("paths").path(route.path()).path(route.method().toLowerCase());
                var effective = op.has("security") ? op.path("security") : api.path("security");
                assertThat(effective).as(route.label()).isEqualTo(route.roles().contains("ANON") ? mapper.createArrayNode() : bearer);
            }
        }
        for (Route route : routes().toList()) {
            int status = mvc.perform(call(route, CLUB, "ANON")).andReturn().getResponse().getStatus();
            assertThat(status == 401).as(route.label() + " without a token → " + status).isEqualTo(!route.roles().contains("ANON"));
        }
        assertThat(routes().filter(r -> r.roles().contains("ANON")).map(Route::label)).containsExactly("POST /api/v1/email-unsubscribes");
    }

    @Test void WP_11_A_T_11_16_snapshotPublishesEveryOperationWithTypedFormsListMetadataAndEnumsOnce() throws Exception {
        var api = mapper.readTree(mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(routes().count()).isEqualTo(21);
        for (Route route : routes().toList()) {
            var op = api.path("paths").path(route.path()).path(route.method().toLowerCase());
            assertThat(op.isMissingNode()).as(route.label()).isFalse();
            assertThat(op.path("description").asText()).as(route.label()).contains("Roles:");
            if (!route.implemented()) { assertThat(op.path("description").asText()).as(route.label()).contains("501"); }
            if (!route.path().endsWith("/export") && !route.implemented()) { assertThat(op.path("description").asText()).as(route.label()).contains("guards"); }
            assertThat(op.path("responses").has(Integer.toString(route.success()))).as(route.label()).isTrue();
            assertThat(op.path("operationId").asText()).as(route.label()).doesNotContain("_");
            assertThat(op.path("parameters").findValuesAsText("name")).doesNotContain("clubId");
            var key = Stream.of(op.has("parameters") ? mapper.convertValue(op.path("parameters"), JsonNode[].class) : new JsonNode[0])
                    .filter(p -> p.path("name").asText().equals("Idempotency-Key")).findFirst();
            assertThat(key.isPresent()).as(route.label() + " Idempotency-Key").isEqualTo(route.idempotency());
            if (route.idempotency()) { assertThat(key.orElseThrow().path("required").asBoolean()).isTrue(); assertThat(key.orElseThrow().at("/schema/format").asText()).isEqualTo("uuid"); }
        }
        assertThat(routes().filter(Route::idempotency).map(Route::label)).containsExactly("POST /api/v1/message-templates/{id}/send");
        var send = api.at("/paths/~1api~1v1~1message-templates~1{id}~1send/post/responses");
        assertThat(send.has("202")).isTrue(); assertThat(send.has("200")).isTrue();
        // Canonical statuses (CATALEG_ERRORS rule 0), whatever S11 §6 writes.
        var create = api.at("/paths/~1api~1v1~1message-templates/post/responses");
        assertThat(create.at("/400/description").asText()).contains("TEMPLATE_SYNTAX_ERROR", "TEMPLATE_UNKNOWN_VARIABLE", "SMS_BODY_REQUIRED", "SMS_BODY_TOO_LONG");
        assertThat(create.at("/422/description").asText()).contains("CHANNEL_NOT_ALLOWED");
        assertThat(api.at("/paths/~1api~1v1~1message-templates~1{id}/put/responses/422/description").asText()).contains("TEMPLATE_MANDATORY");
        assertThat(api.at("/paths/~1api~1v1~1message-templates~1{id}/put/responses/409/description").asText()).contains("STALE_VERSION");
        assertThat(api.at("/paths/~1api~1v1~1message-templates~1{id}~1send/post/responses/422/description").asText()).contains("TEMPLATE_NOT_SENDABLE", "NO_RECIPIENTS");
        assertThat(api.at("/paths/~1api~1v1~1push-subscriptions/post/responses/422/description").asText()).contains("PUSH_SUBSCRIPTION_INVALID");
        assertThat(api.at("/paths/~1api~1v1~1me~1notification-preferences/put/responses/422/description").asText()).contains("INVALID_REMINDER_OPTION");
        assertThat(api.at("/paths/~1api~1v1~1email-unsubscribes/post/responses/422/description").asText()).contains("UNSUBSCRIBE_TOKEN_INVALID");
        // The log: a universal list, exportable, and its export publishes the same contract (S14 R-14-12).
        var log = api.at("/paths/~1api~1v1~1notifications/get");
        assertThat(strings(log.path("x-filterable"))).containsExactly("code", "category", "channel", "status", "memberId", "createdAt");
        assertThat(strings(log.path("x-sortable"))).containsExactly("createdAt");
        assertThat(strings(log.path("x-fields"))).containsExactly("id", "createdAt", "code", "category", "audience", "recipient", "channels", "readAt");
        assertThat(log.path("x-columns").findValuesAsText("key")).containsExactly("createdAt", "code", "recipient", "channels", "readAt");
        assertThat(log.path("x-exportable").asBoolean()).isTrue();
        var export = api.at("/paths/~1api~1v1~1notifications~1export/get");
        for (String extension : List.of("x-filterable", "x-sortable", "x-fields", "x-columns")) { assertThat(export.path(extension)).as(extension).isEqualTo(log.path(extension)); }
        assertThat(api.at("/paths/~1api~1v1~1notifications~1filter-values/get/parameters").findValuesAsText("name")).doesNotContain("fields");
        var schemas = api.at("/components/schemas");
        assertThat(strings(schemas.at("/NotificationListItem/required"))).containsExactly("id");
        // Enums published once, as components referenced by the forms.
        Map<String, Integer> enums = Map.of("NotificationCategory", 5, "NotificationAudience", 4, "NotificationChannel", 4, "DeliveryStatus", 10,
                "NotificationActionType", 13, "TemplateIcon", 16, "TemplateColor", 5, "TemplateKind", 2, "TemplateStatus", 3);
        enums.forEach((name, size) -> assertThat(schemas.path(name).path("enum")).as(name).hasSize(size));
        assertThat(strings(schemas.at("/DeliveryStatus/enum"))).containsExactly("QUEUED", "SENT", "DELIVERED", "FAILED", "SKIPPED_BY_PREFERENCE", "SKIPPED_MODULE_OFF",
                "SKIPPED_NO_CONTACT", "SKIPPED_CAP", "SKIPPED_STALE", "SKIPPED_NOT_ALLOWED"); // E7-T02: the non-prod SMS guard (catalog proposal)
        assertThat(schemas.at("/MessageTemplateListItem/properties/category/$ref").asText()).isEqualTo("#/components/schemas/NotificationCategory");
        assertThat(schemas.at("/ChannelState/properties/status/$ref").asText()).isEqualTo("#/components/schemas/DeliveryStatus");
        assertThat(schemas.at("/MeNotification/properties/icon/$ref").asText()).isEqualTo("#/components/schemas/TemplateIcon");
        assertThat(schemas.at("/FeedAction/properties/type/$ref").asText()).isEqualTo("#/components/schemas/NotificationActionType");
        // Nullable references are unions; the error details are published.
        for (String nullable : List.of("MeNotification/action", "TemplatePreview/sms", "MessageTemplateDetail/seedDefault")) {
            String[] parts = nullable.split("/");
            var field = schemas.path(parts[0]).path("properties").path(parts[1]);
            assertThat(field.path("anyOf").get(0).has("$ref")).as(nullable).isTrue();
            assertThat(field.path("anyOf").get(1).path("type").asText()).as(nullable).isEqualTo("null");
        }
        assertThat(schemas.at("/MissingVariablesDetails/properties/missingVariables/type").asText()).isEqualTo("array");
        assertThat(strings(schemas.at("/NotificationPreferencesRequest/properties/reminderMinutesBefore/type"))).containsExactlyInAnyOrder("integer", "null");
        assertThat(strings(schemas.at("/NotificationPreferencesRequest/required"))).isEmpty();
        // R-11-08: the bounce mark of a member's contact e-mail is published (S03, E2-T06).
        assertThat(strings(schemas.at("/ContactEmail/required"))).contains("email", "bounced");
        // Every fixture form conforms to its schema (required keys present, no unknown key, enums closed, nulls only where nullable).
        var fixtures = mapper.readTree(getClass().getResourceAsStream("/fixtures/contracts/e7-messaging-responses.json"));
        fixtures.fields().forEachRemaining(entry -> assertFixtureSchema(schemas, schemas.path(entry.getKey().split("#")[0]), entry.getValue(), entry.getKey()));
        assertFixtureSchema(schemas, schemas.path("MeNotifications"), mapper.readTree(getClass().getResourceAsStream("/fixtures/contracts/e7-me-notifications.json")), "MeNotifications");
        assertFixtureSchema(schemas, schemas.path("NotificationPreferences"),
                mapper.readTree(getClass().getResourceAsStream("/fixtures/contracts/e7-notification-preferences.json")), "NotificationPreferences");
    }
    private void assertFixtureSchema(JsonNode schemas, JsonNode schema, JsonNode value, String path) {
        if (value.isNull()) { assertThat(nullable(schemas, schema)).as(path + " is null but not nullable").isTrue(); return; }
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
        // A free-form value (Filter.value: «JSON scalar or array») has no items schema to check.
        if (value.isArray() && schema.has("items")) { value.forEach(item -> assertFixtureSchema(schemas, schema.path("items"), item, path + "[]")); }
        if (schema.has("enum")) { assertThat(strings(schema.path("enum"))).as(path).contains(value.asText()); }
    }
    private static boolean nullable(JsonNode schemas, JsonNode schema) {
        if (schema.has("anyOf")) { for (var option : schema.path("anyOf")) { if ("null".equals(option.path("type").asText())) { return true; } } }
        if (strings(schema.path("type")).contains("null") || schema.path("nullable").asBoolean()) { return true; }
        if (schema.has("$ref")) { return strings(schemas.path(schema.path("$ref").asText().substring("#/components/schemas/".length())).path("type")).contains("null"); }
        return false;
    }
    private static List<String> strings(JsonNode node) {
        var values = new ArrayList<String>();
        if (node.isArray()) { node.forEach(v -> values.add(v.asText())); } else if (!node.isMissingNode()) { values.add(node.asText()); }
        return values;
    }
}
