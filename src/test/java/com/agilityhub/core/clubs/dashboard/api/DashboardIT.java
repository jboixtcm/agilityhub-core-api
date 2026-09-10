package com.agilityhub.core.clubs.dashboard.api;

import com.agilityhub.core.clubs.dashboard.application.*;
import com.agilityhub.core.clubs.dashboard.application.DashboardData.*;
import com.agilityhub.core.clubs.dashboard.application.ports.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.definition.*;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.context.annotation.Import(DashboardIT.MongoProbeConfiguration.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = "identity.seed-password=Fictional-dashboard-password")
class DashboardIT extends AbstractIntegrationTest {
    static final List<String> aggregateCommands = new java.util.concurrent.CopyOnWriteArrayList<>();
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class MongoProbeConfiguration {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer dashboardMongoProbe() {
            return builder -> builder.addCommandListener(new com.mongodb.event.CommandListener() {
                @Override public void commandStarted(com.mongodb.event.CommandStartedEvent event) {
                    if (event.getCommandName().equals("aggregate")) { aggregateCommands.add(event.getCommand().getString("aggregate").getValue()); }
                }
            });
        }
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs; @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts;
    @Autowired DashboardQuery dashboard; @Autowired DogActivityQuery activity; @Autowired OutboxDispatcher dispatcher;
    @Autowired ClubDefinitions definitions; @Autowired ClubDefinitionCodec codec;
    @Autowired com.agilityhub.core.clubs.census.application.DemoSeedService demo;
    @Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean ClassOccupancyQuery occupancy; @MockitoBean TrainingBookingsQuery training;
    @MockitoBean BookingActivity bookings; @MockitoBean PendingRequestsQuery requests;
    @MockitoBean FollowUpUnreadQuery unread; @MockitoBean ClassSessionsQuery sessions; @MockitoBean RiskEvaluator evaluator;
    String club, host;
    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2026-08-10T06:00:00Z"));
        club = "dash-" + UUID.randomUUID().toString().substring(0, 8); host = club + ".example.test";
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(club, host));
        tree.set("modules", mapper.valueToTree(Module.values()));
        tree.set("paymentProviders", mapper.valueToTree(Map.of("MANUAL", Map.of(), "SEPA_XML", Map.of()))); clubs.save(mapper.convertValue(tree, Club.class)); hosts.invalidate();
        when(occupancy.sessions(anyString(), any(), any())).thenReturn(List.of());
        when(training.bookings(anyString(), any(), any())).thenReturn(List.of());
        when(bookings.dogsWithBooking(anyString(), any(), any())).thenReturn(Set.of());
        when(requests.counts(anyString())).thenReturn(new PendingRequestsQuery.Counts(0, 0));
        when(sessions.sessions(anyString(), any(), any())).thenReturn(List.of());
    }
    MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("Host", host).with(jwt().jwt(j -> j.subject("dashboard-admin").claim("clubId", club)).authorities(() -> "ROLE_ADMIN"));
    }
    JsonNode result(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return mapper.readTree(response.getContentAsString());
    }
    JsonNode dashboard() throws Exception { return result(admin(get("/api/v1/dashboard")), 200); }
    JsonNode counters() throws Exception { return result(admin(get("/api/v1/dashboard/counters")), 200); }
    void insert(String collection, Document row) { mongo.insert(row.append("clubId", club), collection); }
    Document member(String id, String status) {
        return new Document("_id", id).append("status", status).append("firstName", "Example").append("lastName1", "Applicant")
                .append("createdAt", Date.from(clock.instant())).append("joinedAt", Date.from(clock.instant()));
    }
    void parameter(String key, Object value) {
        mongo.getCollection("parameters").deleteMany(new Document("clubId", club).append("key", key));
        mongo.insert(new Parameter(UUID.randomUUID().toString(), club, key, value, "unknown", "club", null, List.of(), 0L, clock.instant()));
        configs.invalidate(club); dashboard.invalidate(club);
    }
    @Test void T_14_11_seededCensusShapeCacheExpiryAndLocaleVariants() throws Exception {
        clock.setInstant(Instant.parse("2026-09-09T10:00:00Z"));
        var input = codec.read(Path.of("seeds/club-canic.yaml"));
        ((ObjectNode) input.get("club")).put("slug", club).put("status", "ACTIVE");
        ((ObjectNode) input.at("/domains/0")).put("host", host);
        ((ObjectNode) input.at("/domains/1")).put("host", "admin." + host);
        club = definitions.apply(input, false).id(); hosts.invalidate();
        try (var tenant = TenantContext.open(club)) {
            var yaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
            try (var stream = java.nio.file.Files.newInputStream(Path.of("seeds/demo-canic.yaml"))) {
                demo.apply(mapper.convertValue(yaml.load(stream), com.agilityhub.core.clubs.census.domain.DemoDataset.Spec.class), 42);
            }
        }
        aggregateCommands.clear();
        var first = dashboard();
        assertThat(aggregateCommands).containsExactly("members", "members", "levels", "dogs");
        aggregateCommands.clear();
        assertThat(first.at("/kpis/activeMembers/value").asInt()).isEqualTo(184);
        assertThat(first.at("/kpis/pendingSignups/value").asInt()).isEqualTo(3);
        assertThat(first.at("/kpis/pendingSignups/warnDays").asInt()).isEqualTo(2);
        assertThat(first.at("/kpis/classOccupancy/percent").isNull()).isTrue();
        assertThat(first.at("/kpis/classOccupancy/capacity").asInt()).isZero();
        assertThat(first.at("/dogsByLevel/totalActiveDogs").asInt()).isEqualTo(242);
        assertThat(first.at("/dogsByLevel/levels")).hasSize(9);
        assertThat(first.at("/dogsByLevel/levels")).allSatisfy(row -> assertThat(row.path("withRecentBooking").asInt()).isZero());
        assertThat(first.at("/riskReview/items")).isEmpty();
        assertThat(first.at("/pendingSignups/items")).hasSize(3);
        clock.setInstant(clock.instant().plusSeconds(30)); assertThat(dashboard()).isEqualTo(first);
        assertThat(aggregateCommands).isEmpty();
        var spanish = result(admin(get("/api/v1/dashboard").header("Accept-Language", "es")), 200);
        assertThat(spanish.path("generatedAt")).isEqualTo(first.path("generatedAt"));
        assertThat(spanish.at("/dogsByLevel/levels/0/name")).isNotEqualTo(first.at("/dogsByLevel/levels/0/name"));
        clock.setInstant(clock.instant().plusSeconds(30)); assertThat(dashboard().path("generatedAt")).isNotEqualTo(first.path("generatedAt"));
    }
    @Test void T_14_11_validationInvalidatesDashboardAndCountersThroughActualOutbox() throws Exception {
        // Use the public no-plan path; levels still require an assigned level on validation.
        insert("levels", new Document("_id", "validation-" + club).append("code", "A").append("name", Map.of("en", "A"))
                .append("color", "#000000").append("order", 1).append("active", true));
        var body = Map.of("locale", "en", "person", Map.of("idDocument", Map.of("type", "DNI", "value", "12000001G"),
                "firstName", "Example", "lastName1", "Applicant", "birthDate", "2000-01-01", "gender", "FEMALE",
                "emails", List.of(club + "@example.test"), "phones", List.of(Map.of("prefix", "+34", "number", "600000001")),
                "address", Map.of("street", "Example street", "postalCode", "99999", "town", "Example town")),
                "dog", Map.of("name", "Example Dog", "sex", "FEMALE", "breed", "Whippet", "birthMonth", "2024-04", "chip", "941000001234567"),
                "payment", Map.of("type", "MANUAL", "firstMonthOption", "TODAY"),
                "consents", Map.of("privacyPolicy", Map.of("accepted", true, "version", "v1"), "imageUse", Map.of("granted", false, "version", "v1")));
        var created = result(post("/api/v1/signup").header("Host", host).header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json").content(mapper.writeValueAsBytes(body)), 201);
        dispatcher.dispatch();
        var before = dashboard(); assertThat(counters().path("pendingSignups").asInt()).isEqualTo(1);
        String id = created.path("memberId").asText();
        var dog = mongo.getCollection("dogs").find(new Document("clubId", club).append("memberId", id)).first();
        clock.setInstant(clock.instant().plusSeconds(1));
        result(admin(post("/api/v1/members/" + id + "/validation")).contentType("application/json").content(mapper.writeValueAsBytes(
                Map.of("version", 0, "dogs", List.of(Map.of("dogId", dog.getString("_id"), "levelId", "validation-" + club))))), 200);
        assertThat(dashboard().path("generatedAt")).isEqualTo(before.path("generatedAt"));
        dispatcher.dispatch();
        var after = dashboard(); assertThat(after.path("generatedAt")).isNotEqualTo(before.path("generatedAt"));
        assertThat(after.at("/kpis/activeMembers/value").asInt()).isEqualTo(1);
        assertThat(after.at("/kpis/pendingSignups/value").asInt()).isZero(); assertThat(counters().path("pendingSignups").asInt()).isZero();
    }
    @Test void T_14_22_crossTenantJoinsRolesAndRealImpersonationAreDenied() throws Exception {
        insert("members", member("owner-" + club, "ACTIVE"));
        insert("dogs", new Document("_id", "dog-" + club).append("memberId", "owner-" + club).append("status", "ACTIVE").append("levelId", "unknown"));
        assertThat(dashboard().at("/dogsByLevel/totalActiveDogs").asInt()).isEqualTo(1);
        String other = club + "b", otherHost = other + ".example.test"; clubs.save(PlatformFixtures.club(other, otherHost)); hosts.invalidate();
        for (String path : List.of("/api/v1/dashboard", "/api/v1/dashboard/counters")) {
            for (String role : List.of("MEMBER", "INSTRUCTOR", "AGILITYHUB_ADMIN")) {
                result(get(path).header("Host", host).with(jwt().jwt(j -> j.subject(role).claim("clubId", club)).authorities(() -> "ROLE_" + role)), 403);
            }
            result(get(path).header("Host", host), 401);
            result(get(path).header("Host", host).with(jwt().jwt(j -> j.subject("global")).authorities(() -> "ROLE_AGILITYHUB_ADMIN")), 403);
            result(get(path).header("Host", otherHost).with(jwt().jwt(j -> j.subject("admin").claim("clubId", club)).authorities(() -> "ROLE_ADMIN")), 403);
            var b = result(get(path).header("Host", otherHost).with(jwt().jwt(j -> j.subject("admin").claim("clubId", other)).authorities(() -> "ROLE_ADMIN")), 200);
            if (path.endsWith("dashboard")) {
                assertThat(b.at("/kpis/activeMembers/value").asInt()).isZero(); assertThat(b.at("/dogsByLevel/totalActiveDogs").asInt()).isZero();
                assertThat(b.toString()).doesNotContain("owner-" + club, "dog-" + club);
            }
        }
        for (String kind : List.of("admin", "member")) {
            String id = club + kind; var role = kind.equals("admin") ? com.agilityhub.core.identity.domain.Role.ADMIN : com.agilityhub.core.identity.domain.Role.MEMBER;
            mongo.save(new com.agilityhub.core.identity.persistence.Account(id, id + "@example.test", "Example " + kind, "en", null, Set.of(),
                    com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
            mongo.save(new com.agilityhub.core.identity.persistence.Membership(id, id, club, id, Set.of(role), com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, role));
            insert("members", member(id, "ACTIVE").append("accountId", id));
        }
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try (var tenant = TenantContext.open(club)) { issued = impersonations.create(club + "admin", club + "member", "Dashboard denial test"); }
        for (String path : List.of("/api/v1/dashboard", "/api/v1/dashboard/counters")) {
            assertThat(result(get(path).header("Host", host).with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER")), 403).path("code").asText()).isEqualTo("IMPERSONATION_DENIED");
        }
    }
    @Test void T_14_23_minimalClubOmitsDisabledBlocksAndCountersRespectModulesAndOwner() throws Exception {
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(club, host)); tree.set("modules", mapper.createArrayNode());
        mongo.getCollection("clubs").updateOne(new Document("_id", club), new Document("$set", new Document("modules", List.of()))); parameter("levels.enabled", false);
        insert("members", member("pending-" + club, "PENDING"));
        when(requests.counts(club)).thenReturn(new PendingRequestsQuery.Counts(3, 2));
        when(unread.count(club, "dashboard-admin")).thenReturn(0);
        var first = dashboard(); assertThat(first.at("/kpis/trainingBookings").isNull()).isTrue(); assertThat(first.path("dogsByLevel").isNull()).isTrue();
        assertThat(first.at("/pendingSignups/items/0").has("paymentMethodType")).isFalse();
        assertThat(first.at("/pendingSignups/items/0").has("warnings")).isFalse();
        assertThat(counters()).isEqualTo(mapper.valueToTree(new Counters(1, 2, 0)));
        verifyNoInteractions(training, bookings);
        when(requests.counts(club)).thenReturn(new PendingRequestsQuery.Counts(3, 4));
        clock.setInstant(clock.instant().plusSeconds(29)); assertThat(counters().path("pendingRequests").asInt()).isEqualTo(2);
        clock.setInstant(clock.instant().plusSeconds(1)); assertThat(counters().path("pendingRequests").asInt()).isEqualTo(4);
        when(unread.count(club, "second-admin")).thenReturn(5);
        assertThat(result(get("/api/v1/dashboard/counters").header("Host", host).with(jwt().jwt(j -> j.subject("second-admin").claim("clubId", club)).authorities(() -> "ROLE_ADMIN")), 200)
                .path("followUpUnread").asInt()).isEqualTo(5);
        assertThat(counters().path("followUpUnread").asInt()).isZero();
    }
    @Test void T_14_02_monthDeltaIncludesReadmissionsAndEffectiveLocalLeaves() throws Exception {
        clock.setInstant(Instant.parse("2026-08-10T00:30:00Z"));
        insert("members", member("first-" + club, "ACTIVE").append("joinedAt", Date.from(Instant.parse("2026-07-31T22:00:00Z"))));
        insert("members", member("readmission-" + club, "ACTIVE").append("signup", Map.of("readmission", true)));
        insert("members", member("old-" + club, "ACTIVE").append("joinedAt", Date.from(Instant.parse("2026-07-31T21:59:59Z"))));
        insert("members", member("left-" + club, "LEFT").append("joinedAt", Date.from(Instant.parse("2026-06-01T10:00:00Z"))).append("leaveDate", "2026-08-01"));
        insert("members", member("scheduled-" + club, "ACTIVE").append("joinedAt", null).append("leaveDate", "2026-08-31"));
        assertThat(dashboard().at("/kpis/activeMembers")).isEqualTo(mapper.valueToTree(new ActiveMembers(4, 1)));
        assertThat(dashboard().at("/week/start").asText()).isEqualTo("2026-08-10");
        mongo.getCollection("clubs").updateOne(new Document("_id", club), new Document("$set", new Document("timeZone", "America/Argentina/Buenos_Aires")));
        configs.invalidate(club); dashboard.invalidate(club);
        var otherZone = dashboard(); assertThat(otherZone.at("/week/start").asText()).isEqualTo("2026-08-03");
        assertThat(otherZone.at("/kpis/activeMembers/deltaThisMonth").asInt()).isZero();
    }
    @Test void T_14_04_pendingAggregateUsesOldestPendingDogAllWarningsAndTenantScopedJoins() throws Exception {
        String owner = "add-" + club, dog = "old-dog-" + club, recentDog = "recent-dog-" + club, plan = "plan-" + club;
        Instant old = clock.instant().minusSeconds(9 * 86400), recent = clock.instant().minusSeconds(86400);
        insert("plans", new Document("_id", plan).append("name", new Document("values", Map.of("ca", "Abonat", "es", "Abonado"))));
        insert("members", member(owner, "ACTIVE").append("firstName", "Marta").append("lastName1", "Riera")
                .append("signup", Map.of("submittedAt", recent, "planIdRequested", "missing"))
                .append("paymentMethod", Map.of("type", "SEPA_DD"))
                .append("familyGroupClaim", Map.of("status", "NOT_FOUND_PENDING"))
                .append("consents", List.of(Map.of("type", "IMAGE_USE", "granted", true), Map.of("type", "IMAGE_USE", "granted", false))));
        insert("dogs", new Document("_id", dog).append("memberId", owner).append("name", "Kiwi").append("breed", "Whippet").append("status", "PENDING")
                .append("signup", Map.of("submittedAt", old, "planIdRequested", plan, "readmission", true)));
        insert("dogs", new Document("_id", recentDog).append("memberId", owner).append("name", "New Dog").append("status", "PENDING")
                .append("signup", Map.of("submittedAt", recent)));
        insert("dogs", new Document("_id", "active-" + club).append("memberId", owner).append("name", "Existing Dog").append("status", "ACTIVE"));
        insert("dog_documents", new Document("_id", "document-" + club).append("dogId", dog).append("type", "VACCINATION_CARD").append("state", "PENDING"));
        insert("upfront_payments", new Document("_id", "payment-" + club).append("memberId", owner).append("dogId", dog).append("status", "PARTIAL")
                .append("amountDue", Map.of("amountMinor", 5000, "currency", "EUR")).append("amountPaid", Map.of("amountMinor", 1000, "currency", "EUR")));
        insert("members", member("new-" + club, "PENDING").append("signup", Map.of("submittedAt", recent)));
        insert("members", member("left-" + club, "LEFT"));
        insert("dogs", new Document("_id", "left-pending-" + club).append("memberId", "left-" + club).append("status", "PENDING"));
        mongo.insert(new Document("_id", "foreign-dog-" + club).append("clubId", "foreign").append("memberId", owner).append("status", "PENDING"), "dogs");
        var result = dashboard(); var row = result.at("/pendingSignups/items/0");
        assertThat(result.at("/pendingSignups/count").asInt()).isEqualTo(2); assertThat(counters().path("pendingSignups").asInt()).isEqualTo(2);
        assertThat(row.path("shortName").asText()).isEqualTo("Marta R."); assertThat(row.path("planName").asText()).isEqualTo("Abonat");
        assertThat(row.path("pendingDays").asInt()).isEqualTo(9); assertThat(row.path("warnings")).hasSize(6);
        assertThat(row.path("dogs")).hasSize(2); assertThat(row.at("/dogs/0/isAddDog").asBoolean()).isTrue();
        assertThat(row.toString()).doesNotContain("Existing Dog", "foreign-dog");
        assertThat(result.at("/kpis/pendingSignups/olderThanWarn").asInt()).isEqualTo(1);
        assertThat(result(admin(get("/api/v1/dashboard").header("Accept-Language", "es")), 200).at("/pendingSignups/items/0/planName").asText()).isEqualTo("Abonado");
        mongo.getCollection("members").updateOne(new Document("_id", owner), new Document("$push", new Document("consents", new Document("type", "IMAGE_USE").append("granted", true))));
        mongo.getCollection("dog_documents").updateOne(new Document("_id", "document-" + club), new Document("$set", new Document("clubId", "foreign")));
        mongo.getCollection("upfront_payments").updateOne(new Document("_id", "payment-" + club), new Document("$set", new Document("clubId", "foreign")));
        dashboard.invalidate(club);
        assertThat(dashboard().at("/pendingSignups/items/0/warnings").toString()).doesNotContain("NO_IMAGE_CONSENT", "DOCUMENT_PENDING", "UPFRONT_UNPAID");
    }
    @Test void T_14_06_sharedActivityCountsOnlyActiveDogsOfActiveTenantOwners() throws Exception {
        for (String status : List.of("ACTIVE", "INACTIVE", "LEFT")) { insert("members", member(status + club, status)); }
        insert("levels", new Document("_id", "A" + club).append("code", "A").append("active", true).append("order", 1).append("name", Map.of("ca", "A")).append("color", "#000000"));
        insert("levels", new Document("_id", "F" + club).append("code", "F").append("active", false).append("order", 2).append("name", Map.of("ca", "F")).append("color", "#000000"));
        for (int i = 0; i < 6; i++) { insert("dogs", new Document("_id", "activity-" + i + club).append("status", "ACTIVE")
                .append("memberId", "ACTIVE" + club).append("levelId", (i < 4 ? "A" : "F") + club)); }
        for (String owner : List.of("INACTIVE", "LEFT", "FOREIGN")) {
            insert("dogs", new Document("_id", owner + "dog" + club).append("status", "ACTIVE").append("memberId", owner + club).append("levelId", "A" + club));
        }
        mongo.insert(member("FOREIGN" + club, "ACTIVE").append("clubId", "foreign"), "members");
        insert("dogs", new Document("_id", "inactive-dog" + club).append("status", "INACTIVE").append("memberId", "ACTIVE" + club).append("levelId", "A" + club));
        when(bookings.dogsWithBooking(eq(club), eq(Instant.parse("2026-08-02T22:00:00Z")), eq(Instant.parse("2026-08-16T22:00:00Z"))))
                .thenReturn(Set.of("activity-0" + club, "activity-1" + club, "activity-2" + club, "FOREIGNdog" + club));
        var result = dashboard().path("dogsByLevel"); assertThat(result.path("totalActiveDogs").asInt()).isEqualTo(6);
        assertThat(result.path("others").asInt()).isEqualTo(2); assertThat(result.path("levels")).hasSize(1);
        assertThat(result.at("/levels/0/total").asInt()).isEqualTo(4); assertThat(result.at("/levels/0/withRecentBooking").asInt()).isEqualTo(3);
        try (var tenant = TenantContext.open(club)) {
            assertThat(activity.counts(LocalDate.of(2026, 8, 10), ZoneId.of("Europe/Madrid"), 2)).contains(new DogCount("A" + club, 4, 3));
        }
        parameter("coverage.activeDogWeeks", 1); dashboard();
        verify(bookings).dogsWithBooking(club, Instant.parse("2026-08-09T22:00:00Z"), Instant.parse("2026-08-16T22:00:00Z"));
    }
    @Autowired Map<String, DomainEventHandler<?>> handlers;
    @Test void T_14_11_allRegisteredInvalidationsCommitOnlyAndPreserveOtherClubs() throws Exception {
        for (String name : List.of("dashboardSignupSubmitted", "dashboardMemberValidated", "dashboardSignupRejected",
                "dashboardClassCancelledByClub", "dashboardClassAutoCancelled", "dashboardClassAtRisk", "dashboardClubModulesChanged",
                "dashboardParameterChanged", "dashboardDogRegistered", "dashboardSignupEdited", "dashboardMemberStatusChanged", "dashboardLevelChanged", "dashboardClubUpdated")) {
            var before = dashboard(); counters(); clock.setInstant(clock.instant().plusSeconds(1));
            @SuppressWarnings("unchecked") var handler = (DomainEventHandler<DashboardEvents.Event>) handlers.get(name);
            assertThat(handler).isNotNull(); assertThat(handler.eventClass()).isEqualTo(DashboardEvents.Event.class);
            var event = new DashboardEvents.Event(handler.eventType(), club, "Member", "example", clock.instant(), Map.of(), null, null, DomainEvent.Origin.SYSTEM);
            var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
            tx.executeWithoutResult(status -> { try { handler.handle("event", event); } catch (Exception failure) { throw new RuntimeException(failure); } status.setRollbackOnly(); });
            assertThat(dashboard().path("generatedAt")).isEqualTo(before.path("generatedAt"));
            tx.executeWithoutResult(status -> { try { handler.handle("event", event); } catch (Exception failure) { throw new RuntimeException(failure); } });
            assertThat(dashboard().path("generatedAt")).isNotEqualTo(before.path("generatedAt"));
        }
        var before = dashboard(); dashboard.invalidate(null); dashboard.invalidate("foreign");
        assertThat(dashboard().path("generatedAt")).isEqualTo(before.path("generatedAt"));
        dashboard.invalidateAfterCommit(club); clock.setInstant(clock.instant().plusSeconds(1));
        assertThat(dashboard().path("generatedAt")).isNotEqualTo(before.path("generatedAt"));
        clock.setInstant(Instant.parse("2026-08-10T21:59:59Z")); var late = dashboard();
        clock.setInstant(Instant.parse("2026-08-10T22:00:00Z")); assertThat(dashboard().path("today").asText()).isEqualTo("2026-08-11");
        assertThat(dashboard().path("generatedAt")).isNotEqualTo(late.path("generatedAt"));
    }
    @Test void T_14_11_concurrentReadersShareOneClubLoadAndUnauthenticatedCallsFail() throws Exception {
        assertThatThrownBy(() -> dashboard.get("ca")).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        assertThatThrownBy(() -> dashboard.counters()).isInstanceOf(ApiException.class);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var gate = new java.util.concurrent.CountDownLatch(1); var calls = new ArrayList<java.util.concurrent.Future<Snapshot>>();
            for (int i = 0; i < 8; i++) { calls.add(executor.submit(() -> {
                gate.await();
                try (var tenant = TenantContext.open(club); var user = CurrentUser.open(new CurrentUser("admin", "Example", null, DomainEvent.Origin.BACKOFFICE))) {
                    return dashboard.get("unknown");
                }
            })); }
            gate.countDown(); var first = calls.getFirst().get(10, java.util.concurrent.TimeUnit.SECONDS);
            for (var call : calls) { assertThat(call.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(first); }
            verify(occupancy, times(1)).sessions(eq(club), any(), any());
            verify(bookings, times(1)).dogsWithBooking(eq(club), any(), any());
        } finally { executor.shutdownNow(); }
    }
}
