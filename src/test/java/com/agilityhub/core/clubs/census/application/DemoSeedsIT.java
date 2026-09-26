package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.DemoDataset;
import com.agilityhub.core.clubs.census.support.DemoFixtures;
import com.agilityhub.core.clubs.catalogs.application.*;
import com.agilityhub.core.clubs.catalogs.domain.CatalogKind;
import com.agilityhub.core.clubs.catalogs.persistence.Level;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.platform.application.definition.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = "identity.seed-password=Fictional-seed-password")
class DemoSeedsIT extends AbstractIntegrationTest {
    @Autowired ClubDefinitions definitions; @Autowired ClubDefinitionCodec codec; @Autowired DemoSeedService demo;
    @Autowired DemoSeedCommand command; @Autowired CatalogService catalogs; @Autowired PlanService plans; @Autowired PriceService prices;
    @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo;
    @Autowired MockMvc mvc; @Autowired DocumentService documents;
    @BeforeEach void clear() {
        for (String collection : List.of("clubs", "parameters", "accounts", "memberships", "members", "dogs", "family_groups", "dog_documents",
                "levels", "rings", "plans", "prices", "faq_entries", "instructors", "catalog_seed_references", "demo_seed_runs",
                "club_pages", "domain_events", "audit_entries", "catalog_write_locks", "census_write_locks", "attachment_uploads", "upfront_payments")) { mongo.remove(new Query(), collection); }
        hosts.invalidate(); clock.setInstant(java.time.Instant.parse("2026-09-09T10:00:00Z"));
    }
    ObjectNode seed() { return codec.read(Path.of("seeds/club-canic.yaml")); }
    String club() { return definitions.apply(seed(), false).id(); }
    Map<String, List<Document>> snapshot() {
        var result = new TreeMap<String, List<Document>>();
        for (String name : mongo.getCollectionNames()) { result.put(name, mongo.findAll(Document.class, name)); } return result;
    }
    @Test @AuditCovers(AuditAction.CATALOG_CHANGED)
    void T_05_21_catalogSeedAppliesExportsUpdatesAndRepeatsWithoutWrites() throws Exception {
        var input = seed(); var before = snapshot();
        assertThat(definitions.apply(input, true).render(true)).contains("34 catalog changes"); assertThat(snapshot()).isEqualTo(before);
        String club = definitions.apply(input, false).id(); var first = snapshot();
        for (var route : Map.of("levels", 10, "rings", 5, "plans", 6).entrySet()) {
            mvc.perform(get("/api/v1/" + route.getKey()).header("Host", "app.agilitycanic.cat")
                    .with(jwt().jwt(j -> j.subject("seed-admin").claim("clubId", club)).authorities(() -> "ROLE_ADMIN")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(route.getValue()));
        }

        assertThat(definitions.apply(input, false).changes()).isZero(); assertThat(snapshot()).isEqualTo(first);
        try (var tenant = TenantContext.open(club)) {
            assertThat(catalogs.list(CatalogKind.LEVEL, true)).hasSize(10); assertThat(catalogs.list(CatalogKind.RING, true)).hasSize(5);
            assertThat(catalogs.list(CatalogKind.FAQ, true)).hasSize(7); assertThat(plans.list(true)).hasSize(6);
            // B34 (S05 §12): «Competició 1 gos», 40 €/month, assigned by the club (not on the signup nor on the web).
            var competition = plans.list(true).stream().filter(p -> p.code().equals("COMPETICIO_1")).findFirst().orElseThrow();
            assertThat(competition.billingMode().name()).isEqualTo("MONTHLY_FEE"); assertThat(competition.dogsIncluded()).isEqualTo(1);
            assertThat(competition.showOnSignup()).isFalse(); assertThat(competition.showOnWeb()).isFalse(); assertThat(competition.order()).isEqualTo(50);
            assertThat(prices.list(competition.id(), null)).singleElement().satisfies(price -> {
                assertThat(price.concept().name()).isEqualTo("MONTHLY_FEE"); assertThat(price.amount().amountMinor()).isEqualTo(4000);
            });
            // S05 §12 seed, E29 and B32: Teràpia and Pendent are the levels outside the progression.
            assertThat(catalogs.list(CatalogKind.LEVEL, true).stream().map(l -> (Level) l).filter(l -> !l.progression()).map(Level::code)).containsExactly("TER", "PENDENT");
            var pending = catalogs.list(CatalogKind.LEVEL, true).stream().map(l -> (Level) l).filter(l -> l.code().equals("PENDENT")).findFirst().orElseThrow();
            assertThat(pending.order()).isEqualTo(90); assertThat(pending.capacity()).isEqualTo(5); assertThat(pending.color()).isEqualTo("#9AA0A6");
            // The Cànic locales are ca/es (S05 §12 has only name.ca/name.es): a level name in `en` is rejected for this club.
            assertThat(pending.grantsFreeTraining()).isFalse(); assertThat(pending.name().values()).containsOnly(Map.entry("ca", "Pendent"), Map.entry("es", "Pendiente"));
            var therapy = plans.list(true).stream().filter(p -> p.code().equals("TERAPIA")).findFirst().orElseThrow();
            assertThat(therapy.billingMode().name()).isEqualTo("MAINTENANCE");
            assertThat(prices.list(therapy.id(), null).getFirst().amount().amountMinor()).isEqualTo(1000);
            assertThat(prices.list(therapy.id(), null).getFirst().validFrom().toString()).isEqualTo("2026-01-01");
            // Mockup 17 (E3-T10 step 13 and round 2): both pack conditions start with a capital letter.
            for (String code : List.of("PACK6", "PACK10")) {
                var pack = plans.list(true).stream().filter(p -> p.code().equals(code)).findFirst().orElseThrow();
                assertThat(pack.conditions().values().get("ca")).as(code).startsWith("Només un cop");
                assertThat(pack.conditions().values().get("es")).as(code).startsWith("Solo una vez");
            }
            assertThat(plans.list(true).stream().filter(p -> p.code().equals("PACK10")).findFirst().orElseThrow().conditions().values())
                    .containsEntry("ca", "Només un cop · després 40% dte. en matrícula").containsEntry("es", "Solo una vez · después 40% de descuento en matrícula");
        }
        var exported = definitions.export("canic"); codec.validate(exported);
        assertThat(definitions.apply(exported, false).changes()).isZero(); assertThat(snapshot()).isEqualTo(first);
        ((ObjectNode) input.at("/catalogs/levels/0")).put("capacity", 6);
        ((ObjectNode) input.at("/catalogs/rings/0")).put("trainingCapacity", 2);
        ((ObjectNode) input.at("/catalogs/plans/0/name")).put("ca", "Edited membership");
        ((ObjectNode) input.at("/catalogs/faq/0/question")).put("ca", "Edited question?");
        assertThat(definitions.apply(input, true).render(true)).contains("4 catalog changes");
        assertThat(snapshot()).isEqualTo(first); definitions.apply(input, false);
        var updated = snapshot(); assertThat(definitions.apply(input, false).changes()).isZero(); assertThat(snapshot()).isEqualTo(updated);
        ((ObjectNode) input.at("/catalogs/rings/0")).putNull("trainingCapacity"); definitions.apply(input, false);
        assertThat(definitions.apply(input, false).changes()).isZero();
        var price = (ObjectNode) input.at("/catalogs/prices/0/amount"); price.put("amountMinor", 6001);
        assertThatThrownBy(() -> definitions.apply(input, false)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PRICE_LOCKED));
        assertThat(definitions.apply(codec.read(Path.of("seeds/club-minim.yaml")), false).changes()).isPositive();
        var consumer = codec.read(Path.of("seeds/club-canic-consumer.yaml")); var canonical = seed(); consumer.remove("domains"); canonical.remove("domains"); assertThat(consumer).isEqualTo(canonical);
        for (String name : List.of("rules", "privacy")) {
            String body = Files.readString(Path.of("seeds/pages/" + name + ".ca.md"));
            assertThat(body).startsWith("${PROVISIONAL_TEXT}\n\n# ").doesNotContain("ClubPage", "D11", "S05", "S17", "05-09-2026", "plantilla per a clubs", "Esborrany");
        }
    }
    @Test void T_05_21_E29_aStackSeededBeforeLevelProgressionTakesTerapiaOutOfItOnTheNextApply() throws Exception {
        var input = seed(); String club = definitions.apply(input, false).id();
        mongo.updateMulti(new Query(), new org.springframework.data.mongodb.core.query.Update().unset("progression"), "levels"); // levels stored before E29
        var next = definitions.apply(input, true);
        assertThat(next.changes()).as("only the catalogs section differs").isEqualTo(1);
        assertThat(next.render(true)).as("Teràpia and Pendent").contains("2 catalog changes");
        definitions.apply(input, false);
        assertThat(definitions.apply(input, false).changes()).isZero();
        try (var tenant = TenantContext.open(club)) {
            assertThat(catalogs.list(CatalogKind.LEVEL, true).stream().map(l -> (Level) l).filter(l -> !l.progression()).map(Level::code)).containsExactly("TER", "PENDENT");
        }
        var levelChanged = mongo.find(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("type").is("LevelChanged").and("payload.action").is("UPDATED")),
                org.bson.Document.class, "domain_events");
        assertThat(levelChanged).hasSize(2).allSatisfy(e -> assertThat(e.get("payload", org.bson.Document.class).get("diff", org.bson.Document.class))
                .containsOnlyKeys("progression"));
    }
    @Test void T_05_21_duplicateKeysAndInvalidCatalogShapesRollBack() {
        var input = seed(); var levels = (com.fasterxml.jackson.databind.node.ArrayNode) input.at("/catalogs/levels"); levels.add(levels.get(0).deepCopy());
        assertThatThrownBy(() -> definitions.apply(input, true)).isInstanceOf(ApiException.class);
        assertThat(mongo.count(new Query(), "clubs")).isZero();
        for (String section : List.of("levels", "rings", "plans", "prices", "faq")) {
            var invalid = seed(); ((ObjectNode) invalid.at("/catalogs/" + section + "/0")).put("invented", true);
            assertThatThrownBy(() -> definitions.apply(invalid, false)).isInstanceOf(ApiException.class);
        }
        var invalid = seed(); ((ObjectNode) invalid.at("/catalogs/prices/0")).put("planCode", "MISSING");
        assertThatThrownBy(() -> definitions.apply(invalid, false)).isInstanceOf(ApiException.class);
        for (String collection : List.of("clubs", "levels", "plans", "accounts", "domain_events", "audit_entries")) { assertThat(mongo.count(new Query(), collection)).isZero(); }
    }
    @Test void T_03_42_fullDemoCountsDocumentsTeamTenantAndRepeat() throws Exception {
        String club = club();
        command.run(new DefaultApplicationArguments("--club=canic"));
        var saved = snapshot();
        command.run(new DefaultApplicationArguments("--club=canic", "--seed=42")); assertThat(snapshot()).isEqualTo(saved);
        assertThat(definitions.apply(seed(), false).changes()).isZero(); assertThat(snapshot()).isEqualTo(saved);
        try (var tenant = TenantContext.open(club)) {
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(club).and("status").is("ACTIVE")), "members")).isEqualTo(184);
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(club).and("status").is("ACTIVE")), "dogs")).isEqualTo(242);
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(club).and("status").is("PENDING")), "dogs")).isEqualTo(3);
            var doc = mongo.findOne(Query.query(Criteria.where("state").is("RECEIVED")), Document.class, "dog_documents");
            assertThat(doc).isNotNull();
            var rendered = documents.list(doc.getString("dogId")); assertThat(rendered).anyMatch(row -> row.get("state").equals("RECEIVED"));
            // Six active dogs' documents, and the vaccination card of the LEFT member's INACTIVE dog (E5-T24).
            assertThat(mongo.count(Query.query(Criteria.where("state").is("RECEIVED")), "dog_documents")).isEqualTo(7);
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(club).and("status").is("INACTIVE")), "dogs")).isEqualTo(1);
            assertThat(mongo.count(new Query(), "instructors")).isEqualTo(3);
            assertThat(mongo.count(Query.query(Criteria.where("adminProfile.active").is(true)), "memberships")).isEqualTo(2);
            // CATALEG_ESDEVENIMENTS (25-09, E3-T10 round 2): the demo accounts' MembershipChanged use the one spelling too.
            var linked = mongo.find(Query.query(Criteria.where("type").is("MembershipChanged").and("clubId").is(club)), Document.class, "domain_events");
            assertThat(linked).isNotEmpty().allSatisfy(event -> assertThat(event.get("payload", Document.class))
                    .containsKeys("accountId", "clubId", "before", "after").doesNotContainKeys("rolesBefore", "rolesAfter").containsEntry("clubId", club));
            assertThatThrownBy(() -> demo.apply(DemoFixtures.spec(mapper, false), 43)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CLUB_NOT_EMPTY));
        }
        for (String role : List.of("ADMIN", "INSTRUCTOR")) {
            mvc.perform(get("/api/v1/members").param("size", "20").param("filter", "status:eq:ACTIVE").header("Host", "app.agilitycanic.cat")
                    .with(jwt().jwt(j -> j.subject("demo-admin").claim("clubId", club)).authorities(() -> "ROLE_" + role)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(184));
        }
        mvc.perform(get("/api/v1/members").header("Host", "app.agilitycanic.cat")
                .with(jwt().jwt(j -> j.subject("demo-member").claim("clubId", club)).authorities(() -> "ROLE_MEMBER"))).andExpect(status().isForbidden());
        String other = definitions.apply(codec.read(Path.of("seeds/club-minim.yaml")), false).id();
        mvc.perform(get("/api/v1/members").header("Host", "minim.example.test")
                .with(jwt().jwt(j -> j.subject("other-admin").claim("clubId", other)).authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/members").header("Host", "minim.example.test")
                .with(jwt().jwt(j -> j.subject("demo-admin").claim("clubId", club)).authorities(() -> "ROLE_ADMIN"))).andExpect(status().isForbidden());
    }
    @Test void T_05_21_configurationAndCatalogsUseTheSameTransactionWithoutCachingRolledBackValues() throws Exception {
        String club = club(); var original = configs.get(club); var input = seed();
        ((com.fasterxml.jackson.databind.node.ArrayNode) input.path("modules")).add("SINGLE_CLASS");
        var single = ((com.fasterxml.jackson.databind.node.ArrayNode) input.at("/catalogs/plans")).addObject();
        single.put("code", "SINGLE").set("name", mapper.valueToTree(Map.of("ca", "Single example")));
        single.put("type", "SINGLE_CLASS").put("dogsIncluded", 1).put("order", 50).put("active", true).put("showOnSignup", true).put("showOnWeb", true);
        single.putObject("entryFee").put("mode", "NONE");
        single.putObject("singleClass").put("chargeMode", "PAY_TO_BOOK").put("cancelPolicy", "REFUND");
        var invalid = input.deepCopy(); ((ObjectNode) invalid.at("/catalogs/prices/0/amount")).put("currency", "USD");
        assertThatThrownBy(() -> definitions.apply(invalid, false)).isInstanceOf(ApiException.class);
        assertThat(configs.get(club)).isEqualTo(original);
        definitions.apply(input, false);
        try (var tenant = TenantContext.open(club)) { assertThat(plans.list(true)).anyMatch(p -> p.code().equals("SINGLE")); }
        assertThat(definitions.apply(input, false).changes()).isZero();
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Test void T_03_42_demoRollbackLeavesNoCensusIdentityAuditOrCompletionWrites() throws Exception {
        String club = club(); var spec = DemoFixtures.spec(mapper, true); var before = snapshot();
        try (var tenant = TenantContext.open(club)) {
            assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                demo.apply(spec, 42); throw new IllegalStateException("Synthetic rollback");
            })).isInstanceOf(IllegalStateException.class);
        }
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void T_03_42_demoRejectsDeploymentProfilesBeforeWriting() throws Exception {
        for (String profile : List.of("prod", "staging", "local,prod", "test,staging", "unknown")) {
            var environment = new org.springframework.mock.env.MockEnvironment(); environment.setActiveProfiles(profile.split(","));
            var restricted = new DemoSeedService(null, null, null, null, null, null, null, mapper, environment, null, null, null, null, null);
            assertThatThrownBy(() -> restricted.apply(DemoFixtures.spec(mapper, true), 42)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        }
    }
    @Test void T_03_42_smallFixtureUsesSameGeneratorAndExistingCensusIsProtected() throws Exception {
        String club = club(); var spec = DemoFixtures.spec(mapper, true);
        try (var tenant = TenantContext.open(club)) {
            var result = demo.apply(spec, 42); assertThat(result.counts()).containsEntry("activeMembers", 8).containsEntry("activeDogs", 16).containsEntry("pendingDogs", 3)
                    .containsEntry("inactiveDogs", 1).containsEntry("dogs", 20).containsEntry("receivedDocuments", 3).containsEntry("pendingDocuments", 17);
            assertThat(demo.apply(spec, 42).changes()).isZero();
            mongo.remove(new Query(), "demo_seed_runs");
            assertThatThrownBy(() -> demo.apply(spec, 42)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CLUB_NOT_EMPTY));
        }
        for (String[] args : List.of(new String[]{}, new String[]{"--club=canic", "--seed"}, new String[]{"--club=canic", "--seed=x"},
                new String[]{"--club=canic", "--unknown"}, new String[]{"--club=../canic"}, new String[]{"--club=canic", "--club=other"}, new String[]{"canic"})) {
            assertThatThrownBy(() -> command.run(new DefaultApplicationArguments(args))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments("--club=missing"))).isInstanceOf(ApiException.class);
    }
    /**
     * E5-T24 step 3 (web E4-W13 question 3; S04 R-04-06, R-04-07): the demo's LEFT member has the seed's fictional DNI and an
     * INACTIVE chipped dog with a received vaccination card. A readmission with that DNI and chip, as the web's real-core e2e
     * sends it, reuses both: D2 shows the seed dog and its own RECEIVED card. The seed stays idempotent.
     */
    @Test void R_04_06_R_04_07_theDemosLeftMemberHasAnInactiveChippedDogThatAReadmissionReuses() throws Exception {
        var input = seed(); ((ObjectNode) input.get("club")).put("status", "ACTIVE"); String club = definitions.apply(input, false).id();
        var spec = DemoFixtures.spec(mapper, true); var left = spec.leftDogs().getFirst();
        try (var tenant = TenantContext.open(club)) {
            assertThat(demo.apply(spec, 42).counts()).containsEntry("inactiveDogs", 1).containsEntry("leftMembers", 1);
            assertThat(demo.apply(spec, 42).changes()).isZero();
        }
        var member = mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("status").is("LEFT")), Document.class, "members");
        assertThat(member.get("idDocument", Document.class)).containsEntry("type", "DNI").containsEntry("number", left.idDocument());
        var dog = mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("memberId").is(member.getString("_id"))), Document.class, "dogs");
        assertThat(dog).containsEntry("status", "INACTIVE").containsEntry("chip", left.chip()).containsEntry("name", left.dogName()).containsEntry("deactivationReason", "MEMBER_LEFT");
        assertThat(dog.getDate("deactivatedAt")).isEqualTo(member.getDate("leftAt"));
        var card = mongo.findOne(Query.query(Criteria.where("dogId").is(dog.getString("_id")).and("type").is("VACCINATION_CARD")), Document.class, "dog_documents");
        assertThat(card.getString("state")).isEqualTo("RECEIVED"); assertThat(card.getList("files", Document.class)).hasSize(1);
        var config = mapper.readTree(mvc.perform(get("/api/v1/signup").header("Host", "app.agilitycanic.cat")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String version = config.at("/legal/legalTextsVersion").asText();
        String plan = mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("code").is("ABONAT")), Document.class, "plans").getString("_id");
        var body = Map.of("locale", "ca", "website", "", "planId", plan,
                "person", Map.of("idDocument", Map.of("type", "DNI", "value", left.idDocument()), "firstName", "Returning", "lastName1", "Example", "birthDate", "1990-01-01",
                        "gender", "FEMALE", "emails", List.of("returning.demo@example.test"), "phones", List.of(Map.of("prefix", "+34", "number", "600000099")),
                        "address", Map.of("street", "Example street", "postalCode", "99999", "town", "Example town")),
                "dog", Map.of("name", left.dogName(), "sex", "FEMALE", "breed", "Fictional mixed breed", "birthMonth", "2020-09", "chip", left.chip()),
                "payment", Map.of("type", "MANUAL", "firstMonthOption", "TODAY"),
                "consents", Map.of("privacyPolicy", Map.of("accepted", true, "version", version), "imageUse", Map.of("granted", false, "version", version)));
        var submitted = mapper.readTree(mvc.perform(post("/api/v1/signup").header("Host", "app.agilitycanic.cat").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").content(mapper.writeValueAsBytes(body))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(submitted.path("memberId").asText()).isEqualTo(member.getString("_id"));
        var review = mapper.readTree(mvc.perform(get("/api/v1/members/" + member.getString("_id") + "/signup").header("Host", "app.agilitycanic.cat")
                .with(jwt().jwt(j -> j.subject("seed-admin").claim("clubId", club)).authorities(() -> "ROLE_ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(review.path("warnings").toString()).contains("READMISSION");
        assertThat(review.path("dogs")).hasSize(1); assertThat(review.at("/dogs/0/id").asText()).isEqualTo(dog.getString("_id"));
        JsonNode own = null; for (var document : review.at("/dogs/0/readmission/current/documents")) { if ("VACCINATION_CARD".equals(document.path("type").asText())) { own = document; } }
        assertThat(own).as("the reused dog's own card in D2").isNotNull();
        assertThat(own.path("state").asText()).isEqualTo("RECEIVED"); assertThat(own.path("files")).hasSize(1);
    }
    @Test void T_14_11_T_04_34_demoPendingRowsAreReviewableAndPreserveSubsequentEdits() throws Exception {
        String club = club();
        try (var tenant = TenantContext.open(club)) { demo.apply(DemoFixtures.spec(mapper, true), 42); }
        var auth = jwt().jwt(j -> j.subject("seed-admin").claim("clubId", club)).authorities(() -> "ROLE_ADMIN");
        var dashboard = mapper.readTree(mvc.perform(get("/api/v1/dashboard").header("Host", "app.agilitycanic.cat").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(dashboard.at("/pendingSignups/count").asInt()).isEqualTo(3);
        assertThat(dashboard.at("/kpis/pendingSignups/olderThanWarn").asInt()).isEqualTo(1);
        assertThat(dashboard.at("/pendingSignups/items/0/warnings").toString()).contains("ACCOUNT_NOT_PROVIDED");
        assertThat(dashboard.at("/dogsByLevel/totalActiveDogs").asInt()).isEqualTo(16);
        for (var row : dashboard.at("/pendingSignups/items")) {
            String member = row.path("memberId").asText();
            var review = mapper.readTree(mvc.perform(get("/api/v1/members/" + member + "/signup")
                    .header("Host", "app.agilitycanic.cat").with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(review.at("/signup/source").asText()).isEqualTo("PUBLIC");
            assertThat(review.at("/signup/pendingDays").asInt()).isEqualTo(row.path("pendingDays").asInt());
            assertThat(review.at("/dogs").size()).isEqualTo(1);
            assertThat(review.at("/upfront/totalDue/amountMinor").asLong()).isPositive();
            assertThat(review.at("/proposals/nextInvoiceDate").asText()).isNotEmpty();
            assertThat(review.at("/member/number").isNull() || review.at("/member/number").isMissingNode()).isTrue();
            assertThat(mongo.findById(member, Document.class, "members").get("accountId")).isNull();
        }
        String member = dashboard.at("/pendingSignups/items/0/memberId").asText();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(member)), Update.update("internalNotes", "Fictional reviewer edit"), "members");
        var before = snapshot(); clock.advance(java.time.Duration.ofDays(1));
        try (var tenant = TenantContext.open(club)) { assertThat(demo.apply(DemoFixtures.spec(mapper, true), 42).changes()).isZero(); }
        assertThat(snapshot()).isEqualTo(before);
        var review = mapper.readTree(mvc.perform(get("/api/v1/members/" + member + "/signup")
                .header("Host", "app.agilitycanic.cat").with(auth)).andReturn().getResponse().getContentAsString());
        var validation = mapper.createObjectNode().put("version", review.path("version").asLong())
                .put("nextInvoiceDate", review.at("/proposals/nextInvoiceDate").asText());
        validation.set("upfrontAmountPaid", review.at("/upfront/totalDue"));
        validation.putArray("dogs").addObject().put("dogId", review.at("/dogs/0/id").asText())
                .put("levelId", review.at("/proposals/levels/0/id").asText());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/members/" + member + "/validation")
                .header("Host", "app.agilitycanic.cat").with(auth).contentType("application/json").content(mapper.writeValueAsBytes(validation)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.number").isNumber()).andExpect(jsonPath("$.accountId").isNotEmpty());
        assertThat(mongo.findById(member, Document.class, "members").getString("status")).isEqualTo("ACTIVE");
    }
}
