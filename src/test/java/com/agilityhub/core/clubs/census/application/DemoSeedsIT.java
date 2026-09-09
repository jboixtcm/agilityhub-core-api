package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.DemoDataset;
import com.agilityhub.core.clubs.census.support.DemoFixtures;
import com.agilityhub.core.clubs.catalogs.application.*;
import com.agilityhub.core.clubs.catalogs.domain.CatalogKind;
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
                "club_pages", "domain_events", "audit_entries", "catalog_write_locks", "census_write_locks", "attachment_uploads")) { mongo.remove(new Query(), collection); }
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
        assertThat(definitions.apply(input, true).render(true)).contains("31 catalog changes"); assertThat(snapshot()).isEqualTo(before);
        String club = definitions.apply(input, false).id(); var first = snapshot();
        for (var route : Map.of("levels", 9, "rings", 5, "plans", 5).entrySet()) {
            mvc.perform(get("/api/v1/" + route.getKey()).header("Host", "app.agilitycanic.cat")
                    .with(jwt().jwt(j -> j.subject("seed-admin").claim("clubId", club)).authorities(() -> "ROLE_ADMIN")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(route.getValue()));
        }

        assertThat(definitions.apply(input, false).changes()).isZero(); assertThat(snapshot()).isEqualTo(first);
        try (var tenant = TenantContext.open(club)) {
            assertThat(catalogs.list(CatalogKind.LEVEL, true)).hasSize(9); assertThat(catalogs.list(CatalogKind.RING, true)).hasSize(5);
            assertThat(catalogs.list(CatalogKind.FAQ, true)).hasSize(7); assertThat(plans.list(true)).hasSize(5);
            var therapy = plans.list(true).stream().filter(p -> p.code().equals("TERAPIA")).findFirst().orElseThrow();
            assertThat(therapy.billingMode().name()).isEqualTo("MAINTENANCE");
            assertThat(prices.list(therapy.id(), null).getFirst().amount().amountMinor()).isEqualTo(1000);
            assertThat(prices.list(therapy.id(), null).getFirst().validFrom().toString()).isEqualTo("2026-01-01");
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
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(club)), "dogs")).isEqualTo(242);
            var doc = mongo.findOne(Query.query(Criteria.where("state").is("RECEIVED")), Document.class, "dog_documents");
            assertThat(doc).isNotNull();
            var rendered = documents.list(doc.getString("dogId")); assertThat(rendered).anyMatch(row -> row.get("state").equals("RECEIVED"));
            assertThat(mongo.count(Query.query(Criteria.where("state").is("RECEIVED")), "dog_documents")).isEqualTo(6);
            assertThat(mongo.count(new Query(), "instructors")).isEqualTo(3);
            assertThat(mongo.count(Query.query(Criteria.where("adminProfile.active").is(true)), "memberships")).isEqualTo(2);
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
            var restricted = new DemoSeedService(null, null, null, null, null, null, null, mapper, environment, null, null, null);
            assertThatThrownBy(() -> restricted.apply(DemoFixtures.spec(mapper, true), 42)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        }
    }
    @Test void T_03_42_smallFixtureUsesSameGeneratorAndExistingCensusIsProtected() throws Exception {
        String club = club(); var spec = DemoFixtures.spec(mapper, true);
        try (var tenant = TenantContext.open(club)) {
            var result = demo.apply(spec, 42); assertThat(result.counts()).containsEntry("activeMembers", 8).containsEntry("dogs", 16);
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
}
