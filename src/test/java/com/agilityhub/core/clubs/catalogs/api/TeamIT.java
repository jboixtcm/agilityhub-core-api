package com.agilityhub.core.clubs.catalogs.api;

import com.agilityhub.core.clubs.catalogs.application.*;
import com.agilityhub.core.clubs.catalogs.persistence.Instructor;
import com.agilityhub.core.identity.application.TeamMembershipService;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.domain.events.MemberStatusChanged;
import com.agilityhub.core.shared.persistence.DomainEventRecord;
import com.agilityhub.core.support.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class TeamIT extends AbstractIntegrationTest {
    static final String CLUB = "team-a", OTHER = "team-b";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired RoleAssignmentService team;
    @Autowired TeamMembershipService memberships;
    @Autowired InstructorUsageCounter usage;
    @Autowired MongoTransactionManager transactionManager;
    @Autowired EventPublisher events;
    @Autowired OutboxDispatcher dispatcher;

    @BeforeEach void seed() {
        TenantContext.clear();
        for (String collection : List.of("clubs", "parameters", "accounts", "memberships", "members", "instructors", "audit_entries", "domain_events",
                "class_sessions", "template_classes", "team_write_locks")) { mongo.remove(new Query(), collection); }
        mongo.remove(new Query(), AuditEntry.class); mongo.remove(new Query(), DomainEventRecord.class);
        clubs.save(PlatformFixtures.club(CLUB, CLUB + ".example.test")); clubs.save(PlatformFixtures.club(OTHER, OTHER + ".example.test"));
        configs.invalidate(CLUB); configs.invalidate(OTHER); hosts.invalidate();
        member(CLUB, "one", "ACTIVE", Set.of(Role.MEMBER)); member(CLUB, "two", "ACTIVE", Set.of(Role.MEMBER));
        member(OTHER, "foreign", "ACTIVE", Set.of(Role.MEMBER, Role.ADMIN));
    }
    void member(String club, String id, String status, Set<Role> roles) {
        mongo.insert(new Account(id + "-account", id + "@example.test", "Example " + id, "en", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        mongo.insert(new Membership(id + "-membership", id + "-account", club, id, roles, Membership.Status.ACTIVE, Role.MEMBER));
        mongo.insert(new Document("_id", id).append("clubId", club).append("accountId", id + "-account").append("status", status)
                .append("firstName", "Example"), "members");
    }
    ResultActions call(MockHttpServletRequestBuilder request, String club, String role) throws Exception {
        return mvc.perform(request.header("Host", club + ".example.test").with(jwt().jwt(j -> j.subject("operator")
                .claim("name", "Example Operator").claim("clubId", club)).authorities(() -> "ROLE_" + role)));
    }
    ResultActions admin(MockHttpServletRequestBuilder request) throws Exception { return call(request, CLUB, "ADMIN"); }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType("application/json").content(mapper.writeValueAsString(body));
    }
    JsonNode json(ResultActions action, int status) throws Exception {
        return mapper.readTree(action.andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    JsonNode instructor(String member) throws Exception {
        return json(admin(body(post("/api/v1/instructors"), Map.of("memberId", member, "shortName", "Example", "color", "#123456"))), 201);
    }
    JsonNode administrator(String member) throws Exception {
        return json(admin(body(post("/api/v1/administrators"), Map.of("memberId", member, "shortName", "Example", "since", "2020-01-01"))), 201);
    }
    JsonNode update(String path, Object input) throws Exception { return json(admin(body(patch("/api/v1/" + path), input)), 200); }
    Membership membership(String id) { return mongo.findById(id + "-membership", Membership.class); }
    void state(String id, String state) { mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("status", state), "members"); }
    void reference(String collection, String club, String instructor, String status, long hours) {
        mongo.insert(new Document("_id", UUID.randomUUID().toString()).append("clubId", club).append("instructorIds", List.of(instructor))
                .append("status", status).append("startsAt", Date.from(clock.instant().plusSeconds(hours * 3600))), collection);
    }
    List<DomainEventRecord> event(String type) { return mongo.find(Query.query(Criteria.where("type").is(type)), DomainEventRecord.class); }

    @Test @AuditCovers({AuditAction.MEMBER_ROLES_CHANGED, AuditAction.CATALOG_CHANGED})
    void T_05_13_createReactivateRolesAndAtomicAudit() throws Exception {
        var created = instructor("one"); String id = created.path("id").asText();
        assertThat(created.path("version").asLong()).isZero();
        assertThat(created.path("lastChange").path("action").asText()).isEqualTo("CATALOG_CHANGED");
        assertThat(membership("one").roles()).containsExactlyInAnyOrder(Role.MEMBER, Role.INSTRUCTOR);
        assertThat(membership("one").instructorId()).isEqualTo(id);
        assertThat(event("MembershipChanged")).singleElement().satisfies(row -> {
            assertThat(row.payload().get("rolesAfter")).asList().containsExactlyInAnyOrder("INSTRUCTOR", "MEMBER");
            assertThat(row.actorAccountId()).isEqualTo("operator");
        });
        assertThat(mongo.find(Query.query(Criteria.where("action").is("MEMBER_ROLES_CHANGED")), AuditEntry.class)).singleElement().satisfies(audit -> {
            assertThat(audit.memberId()).isEqualTo("one"); assertThat(audit.changes()).singleElement().satisfies(change -> assertThat(change.path()).isEqualTo("roles"));
        });
        update("instructors/" + id, Map.of("active", false, "version", 0));
        assertThat(membership("one").roles()).containsExactly(Role.MEMBER);
        var reactivated = json(admin(body(post("/api/v1/instructors"), Map.of("memberId", "one", "shortName", "Updated", "color", "#654321"))), 200);
        assertThat(reactivated.path("id").asText()).isEqualTo(id); assertThat(reactivated.path("version").asLong()).isEqualTo(2);
        update("instructors/" + id, Map.of("shortName", "Final", "version", 2));
        admin(body(patch("/api/v1/instructors/" + id), Map.of("color", "#112233", "version", 2))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        var stored = mongo.findById(id, Instructor.class);
        assertThat(stored.createdAt()).isEqualTo(clock.instant()); assertThat(stored.createdByAccountId()).isEqualTo("operator");
        admin(delete("/api/v1/instructors/" + id)).andExpect(status().isNoContent());
        assertThat(membership("one").instructorId()).isNull(); assertThat(membership("one").roles()).containsExactly(Role.MEMBER);
        state("one", "PENDING");
        admin(body(post("/api/v1/instructors"), Map.of("memberId", "one", "shortName", "Pending", "color", "#123456")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MEMBER_NOT_ACTIVE"));
    }

    @Test void T_05_14_futureAndHistoricalReferencesAndTemplateOnlyDeactivation() throws Exception {
        String id = instructor("one").path("id").asText();
        reference("class_sessions", OTHER, id, "PLANNED", 5);
        reference("class_sessions", CLUB, id, "CANCELLED", 5);
        reference("template_classes", CLUB, id, "ACTIVE", 0);
        update("instructors/" + id, Map.of("active", false, "version", 0));
        assertThat(event("InstructorChanged").getLast().payload().get("action")).isEqualTo("DEACTIVATED");
        update("instructors/" + id, Map.of("active", true, "version", 1));
        reference("class_sessions", CLUB, id, "PLANNED", 5);
        admin(body(patch("/api/v1/instructors/" + id), Map.of("active", false, "version", 2)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSTRUCTOR_IN_USE"))
                .andExpect(jsonPath("$.details.futureClassSessions").value(1));
        try (var scope = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> team.setRoles("one", Set.of("MEMBER"))).hasMessage("INSTRUCTOR_IN_USE");
            assertThat(usage.usage(id).templateClasses()).isEqualTo(1);
        }
        admin(delete("/api/v1/instructors/" + id)).andExpect(status().isConflict());
        mongo.remove(new Query(), "class_sessions");
        admin(delete("/api/v1/instructors/" + id)).andExpect(status().isConflict());
        mongo.remove(new Query(), "template_classes"); reference("class_sessions", CLUB, id, "COMPLETED", -10);
        admin(delete("/api/v1/instructors/" + id)).andExpect(status().isConflict());
        update("instructors/" + id, Map.of("active", false, "version", 2));
    }

    @Test void T_05_15_lastAdminEveryPathAndProfileHistory() throws Exception {
        var first = administrator("one"); long v = first.path("version").asLong();
        admin(body(patch("/api/v1/administrators/one-membership"), Map.of("active", false, "version", v)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        admin(delete("/api/v1/administrators/one-membership")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        try (var scope = TenantContext.open(CLUB)) { assertThatThrownBy(() -> team.setRoles("one", Set.of("MEMBER"))).hasMessage("LAST_ADMIN"); }
        administrator("two");
        update("administrators/one-membership", Map.of("active", false, "version", v));
        assertThat(membership("one").adminProfile().active()).isFalse(); assertThat(membership("one").roles()).containsExactly(Role.MEMBER);
        assertThat(json(admin(get("/api/v1/administrators")), 200).path("items")).hasSize(1);
        assertThat(json(admin(get("/api/v1/administrators").param("includeInactive", "true")), 200).path("items")).hasSize(2);
        var revived = json(admin(body(post("/api/v1/administrators"), Map.of("memberId", "one", "shortName", "Revised", "since", "2021-01-01"))), 200);
        long revision = revived.path("version").asLong();
        update("administrators/one-membership", Map.of("shortName", "Final", "since", "2022-01-01", "version", revision));
        admin(body(patch("/api/v1/administrators/one-membership"), Map.of("active", false, "version", revision))).andExpect(status().isConflict());
        // Legacy identity status updates must preserve the new profile and version.
        try (var scope = TenantContext.open(CLUB)) {
            var old = membership("one");
            mongo.updateFirst(Query.query(Criteria.where("_id").is(old.id())), new Update().set("unrelated", "preserved"), Membership.class);
        }
        admin(delete("/api/v1/administrators/one-membership")).andExpect(status().isNoContent());
        assertThat(membership("one").adminProfile()).isNull();
        assertThat(mongo.getCollection("memberships").find(new Document("_id", "one-membership")).first().getString("unrelated")).isEqualTo("preserved");
        admin(delete("/api/v1/administrators/one-membership")).andExpect(status().isNotFound());
    }

    String leaveEvent(String member, String after, LocalDate effectiveDate) {
        return new TransactionTemplate(transactionManager).execute(tx -> events.publish(new MemberStatusChanged(CLUB, member, clock.instant(),
                Map.of("memberId", member, "before", "ACTIVE", "after", after, "effectiveDate", effectiveDate.toString()), null, null, DomainEvent.Origin.SYSTEM)));
    }
    @Test void T_05_16_outboxLeaveDeactivatesDespiteClassesAndKeepsLastAdmin() throws Exception {
        String first = instructor("one").path("id").asText(); administrator("one"); administrator("two");
        reference("class_sessions", CLUB, first, "PLANNED", 12); state("one", "LEFT");
        String eventId = leaveEvent("one", "LEFT", LocalDate.of(2026, 1, 1)); dispatcher.dispatch();
        assertThat(membership("one").roles()).isEmpty(); assertThat(membership("one").status()).isEqualTo(Membership.Status.SUSPENDED);
        assertThat(membership("one").adminProfile().active()).isFalse(); assertThat(mongo.findById(first, Instructor.class).active()).isFalse();
        assertThat(mongo.findById(eventId, DomainEventRecord.class).status()).isEqualTo(DomainEventRecord.Status.PUBLISHED);
        long audits = mongo.count(new Query(), AuditEntry.class); dispatcher.dispatch(); assertThat(mongo.count(new Query(), AuditEntry.class)).isEqualTo(audits);
        String second = instructor("two").path("id").asText(); state("two", "LEFT");
        leaveEvent("two", "LEFT", LocalDate.of(2026, 1, 1)); dispatcher.dispatch();
        assertThat(membership("two").roles()).containsExactly(Role.ADMIN); assertThat(membership("two").adminProfile().active()).isTrue();
        assertThat(mongo.findById(second, Instructor.class).active()).isFalse();
        assertThat(mongo.find(Query.query(Criteria.where("reason").is("LAST_ADMIN_KEPT")), AuditEntry.class)).singleElement().satisfies(row -> {
            assertThat(row.action()).isEqualTo(AuditAction.MEMBER_ROLES_CHANGED); assertThat(row.actorRole()).isEqualTo("SYSTEM");
        });
        assertThat(TenantContext.current()).isNull(); assertThat(membership("foreign").roles()).contains(Role.ADMIN);
    }

    @Test void T_05_16_staleFutureAndUnlinkedLeaveEventsAreHarmless() {
        leaveEvent("one", "ACTIVE", LocalDate.of(2026, 1, 1));
        leaveEvent("one", "LEFT", LocalDate.of(2026, 1, 1));
        leaveEvent("one", "LEFT", LocalDate.of(2099, 1, 1));
        state("two", "LEFT"); mongo.remove(Query.query(Criteria.where("_id").is("two-membership")), Membership.class);
        leaveEvent("two", "LEFT", LocalDate.of(2026, 1, 1)); dispatcher.dispatch();
        assertThat(membership("one").roles()).containsExactly(Role.MEMBER);
        assertThat(event("MemberStatusChanged")).allSatisfy(row -> assertThat(row.status()).isEqualTo(DomainEventRecord.Status.PUBLISHED));
    }

    @Test void T_03_18_rolesEndpointCreatesDefaultsAndRejectsSelfRemoval() throws Exception {
        var roles = Map.of("roles", List.of("MEMBER", "INSTRUCTOR", "ADMIN"));
        admin(body(put("/api/v1/members/one/roles"), roles)).andExpect(status().isOk()).andExpect(jsonPath("$.roles.length()").value(3));
        assertThat(membership("one").adminProfile().shortName()).isEqualTo("Example");
        assertThat(mongo.findById(membership("one").instructorId(), Instructor.class).shortName()).isEqualTo("Example");
        long eventsBefore = mongo.count(new Query(), DomainEventRecord.class), auditsBefore = mongo.count(new Query(), AuditEntry.class);
        admin(body(put("/api/v1/members/one/roles"), roles)).andExpect(status().isOk());
        assertThat(mongo.count(new Query(), DomainEventRecord.class)).isEqualTo(eventsBefore);
        assertThat(mongo.count(new Query(), AuditEntry.class)).isEqualTo(auditsBefore);
        admin(body(put("/api/v1/members/one/roles"), Map.of("roles", List.of("ADMIN")))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ROLE_MEMBER_REQUIRED"));
        mvc.perform(body(put("/api/v1/members/one/roles"), Map.of("roles", List.of("MEMBER"))).header("Host", CLUB + ".example.test")
                .with(jwt().jwt(j -> j.subject("one-account").claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CANNOT_CHANGE_OWN_ADMIN_ROLE"));
        administrator("two");
        admin(body(put("/api/v1/members/one/roles"), Map.of("roles", List.of("MEMBER")))).andExpect(status().isOk());
        assertThat(membership("one").adminProfile().active()).isFalse();
        admin(body(put("/api/v1/members/one/roles"), roles)).andExpect(status().isOk());
        assertThat(membership("one").adminProfile().active()).isTrue();
    }

    @Test void T_03_18_roleRouteDeniesOtherRolesTenantsAndInvalidTargets() throws Exception {
        var input = Map.of("roles", List.of("MEMBER", "INSTRUCTOR"));
        String path = "/api/v1/members/one/roles";
        mvc.perform(body(put(path), input).header("Host", CLUB + ".example.test")).andExpect(status().isUnauthorized());
        for (String role : List.of("MEMBER", "INSTRUCTOR", "AGILITYHUB_ADMIN")) {
            call(body(put(path), input), CLUB, role).andExpect(status().isForbidden());
        }
        call(body(put(path), input), OTHER, "ADMIN").andExpect(status().isNotFound());
        mvc.perform(body(put(path), input).header("Host", OTHER + ".example.test").with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
        mvc.perform(body(put(path), input).header("Host", CLUB + ".example.test").with(jwt().jwt(j -> j.claim("clubId", CLUB).claim("imp", true)).authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isUnauthorized());
        admin(body(put(path), Map.of("roles", Arrays.asList("MEMBER", null)))).andExpect(status().isBadRequest());
        state("one", "LEFT"); admin(body(put(path), input)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MEMBER_NOT_ACTIVE"));
        state("one", "ACTIVE");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("one-account")), new Update().set("status", "BLOCKED"), Account.class);
        admin(body(put(path), input)).andExpect(status().isUnprocessableEntity());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("one-account")), new Update().set("status", "ACTIVE"), Account.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("one-membership")), new Update().set("status", "SUSPENDED"), Membership.class);
        admin(body(put(path), input)).andExpect(status().isUnprocessableEntity());
        mongo.remove(Query.query(Criteria.where("_id").is("one-membership")), Membership.class);
        admin(body(put(path), input)).andExpect(status().isUnprocessableEntity());
        assertThat(mongo.count(new Query(), Instructor.class)).isZero();
    }

    @Test void T_05_13_defaultsUseClubDateAndThemeAndIdentityUpdatesPreserveProfile() throws Exception {
        clock.setInstant(Instant.parse("2026-01-01T23:30:00Z"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("one")), new Update().unset("firstName"), "members");
        try (var tenant = TenantContext.open(CLUB)) {
            team.setRoles("one", Set.of("MEMBER", "ADMIN", "INSTRUCTOR"));
            assertThat(membership("one").adminProfile().since()).isEqualTo(LocalDate.of(2026, 1, 2));
            assertThat(mongo.findById(membership("one").instructorId(), Instructor.class).color()).isEqualTo(configs.get(CLUB).ringPalette().getFirst());
            var profile = membership("one").adminProfile();
            identityMemberships.suspend("one-account"); assertThat(membership("one").adminProfile()).isEqualTo(profile);
            identityMemberships.resume("one-account"); assertThat(membership("one").adminProfile()).isEqualTo(profile);
        }
        assertThat(membership("one").version()).isEqualTo(3);
    }
    @Autowired com.agilityhub.core.identity.application.MembershipService identityMemberships;

    @Test void T_05_13_auditInsertFailureRollsBackTeamChanges() {
        String collection = mongo.getCollectionName(AuditEntry.class);
        mongo.executeCommand(new Document("collMod", collection).append("validator", new Document("mustNeverExist", new Document("$exists", true))));
        try (var tenant = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> team.setRoles("one", Set.of("MEMBER", "ADMIN", "INSTRUCTOR"))).isInstanceOf(RuntimeException.class);
        } finally { mongo.executeCommand(new Document("collMod", collection).append("validator", new Document())); }
        assertThat(membership("one").roles()).containsExactly(Role.MEMBER); assertThat(membership("one").adminProfile()).isNull();
        assertThat(mongo.count(new Query(), Instructor.class)).isZero(); assertThat(mongo.count(new Query(), DomainEventRecord.class)).isZero();
    }

    @Test void T_05_15_concurrentRemovalCannotLeaveZeroAdmins() throws Exception {
        administrator("one"); administrator("two");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            List<Future<String>> attempts = new ArrayList<>();
            for (String member : List.of("one", "two")) {
                attempts.add(pool.submit(() -> { gate.await(); try (var tenant = TenantContext.open(CLUB)) {
                    try { team.setRoles(member, Set.of("MEMBER")); return "OK"; } catch (ApiException failure) { return failure.getMessage(); }
                } }));
            }
            gate.countDown();
            assertThat(List.of(attempts.get(0).get(20, TimeUnit.SECONDS), attempts.get(1).get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "LAST_ADMIN");
        }
        try (var tenant = TenantContext.open(CLUB)) { assertThat(memberships.activeAdmins()).isEqualTo(1); }
    }

    @Test void T_05_13_rollbackIncludesProfilesMembershipAuditAndEvents() {
        long before = mongo.count(new Query(), DomainEventRecord.class);
        try (var tenant = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                team.setRoles("one", Set.of("MEMBER", "INSTRUCTOR", "ADMIN")); throw new IllegalStateException("rollback");
            })).hasMessage("rollback");
        }
        assertThat(membership("one").roles()).containsExactly(Role.MEMBER); assertThat(membership("one").adminProfile()).isNull();
        assertThat(mongo.count(new Query(), Instructor.class)).isZero(); assertThat(mongo.count(new Query(), AuditEntry.class)).isZero();
        assertThat(mongo.count(new Query(), DomainEventRecord.class)).isEqualTo(before);
    }

    MockHttpServletRequestBuilder route(String method, String path, String id, Object create) throws Exception {
        return switch (method) {
            case "GET" -> get(path);
            case "POST" -> body(post(path), create);
            case "PATCH" -> body(patch(path + "/" + id), Map.of("shortName", "Other", "version", 0));
            default -> delete(path + "/" + id);
        };
    }
    @ParameterizedTest @ValueSource(strings = {"instructors", "administrators"})
    void T_05_19_T_05_20_eachTeamRouteEnforcesRolesTenantAndValidation(String resource) throws Exception {
        var created = resource.equals("instructors") ? instructor("one") : administrator("one");
        String id = resource.equals("instructors") ? created.path("id").asText() : "one-membership";
        Map<String, Object> create = resource.equals("instructors") ? Map.of("memberId", "two", "shortName", "Two", "color", "#123456")
                : Map.of("memberId", "two", "shortName", "Two", "since", "2020-01-01");
        String path = "/api/v1/" + resource;
        for (String method : List.of("GET", "POST", "PATCH", "DELETE")) {
            mvc.perform(route(method, path, id, create).header("Host", CLUB + ".example.test")).andExpect(status().isUnauthorized());
            for (String role : List.of("MEMBER", "INSTRUCTOR", "AGILITYHUB_ADMIN")) {
                var result = call(route(method, path, id, create), CLUB, role);
                if (resource.equals("instructors") && method.equals("GET") && !role.equals("AGILITYHUB_ADMIN")) {
                    result.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].memberId").doesNotExist()).andExpect(jsonPath("$.items[0].usage").doesNotExist());
                } else { result.andExpect(status().isForbidden()); }
            }
            mvc.perform(route(method, path, id, create).header("Host", OTHER + ".example.test").with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
            mvc.perform(route(method, path, id, create).header("Host", "id.example.test").with(jwt().authorities(() -> "ROLE_ADMIN")))
                    .andExpect(status().isForbidden());
            mvc.perform(route(method, path, id, create).header("Host", CLUB + ".example.test").with(jwt().jwt(j -> j.claim("clubId", CLUB).claim("imp", true)).authorities(() -> "ROLE_ADMIN")))
                    .andExpect(status().isUnauthorized());
        }
        call(body(patch(path + "/" + id), Map.of("shortName", "Other", "version", 0)), OTHER, "ADMIN").andExpect(status().isNotFound());
        call(delete(path + "/" + id), OTHER, "ADMIN").andExpect(status().isNotFound());
        var foreignInput = new HashMap<>(create); foreignInput.put("memberId", "foreign");
        admin(body(post(path), foreignInput)).andExpect(status().isNotFound());
        assertThat(json(call(get(path), OTHER, "ADMIN"), 200).path("items")).isEmpty();
        admin(body(patch(path + "/" + id), Map.of("shortName", " ", "version", created.path("version").asLong()))).andExpect(status().isBadRequest());
        admin(body(patch(path + "/" + id), Map.of("shortName", "Valid"))).andExpect(status().isBadRequest());
        if (resource.equals("instructors")) {
            call(get(path).param("includeInactive", "true"), CLUB, "MEMBER").andExpect(status().isForbidden());
            admin(body(post(path), Map.of("memberId", "two", "shortName", "Valid", "color", "red"))).andExpect(status().isBadRequest());
        } else {
            admin(body(post(path), Map.of("memberId", "two", "shortName", "Valid", "since", "2099-01-01"))).andExpect(status().isBadRequest());
        }
        assertThat(TenantContext.current()).isNull();
    }
}
