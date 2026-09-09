package com.agilityhub.core.platform.application.definition;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.platform.application.ClubAdminProvisioner;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.Parameter;
import com.agilityhub.core.platform.persistence.ParameterRepository;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.persistence.audit.AuditRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = "identity.seed-password=Fictional-seed-password")
class ClubDefinitionsIT extends AbstractIntegrationTest {
    @Autowired ClubDefinitions definitions;
    @Autowired ClubDefinitionCodec codec;
    @Autowired ClubDefinitionWriter writer;
    @Autowired ClubDefinitionMapper mapping;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired ClubRepository clubs;
    @Autowired ParameterRepository parameters;
    @Autowired AccountRepository accounts;
    @Autowired MembershipRepository memberships;
    @Autowired ClubAdminProvisioner admins;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuditRepository audits;

    @BeforeEach void emptyDatabase() {
        reset(audits);
        for (String collection : List.of("clubs", "parameters", "accounts", "memberships", "domain_events", "audit_entries", "club_pages")) {
            mongo.remove(new Query(), collection);
        }
        hosts.invalidate();
    }
    ObjectNode seed(String name) { return codec.read(Path.of("seeds/club-" + name + ".yaml")); }
    long count(String collection) { return mongo.count(new Query(), collection); }
    void failure(ObjectNode definition, ErrorCode code) {
        assertThatThrownBy(() -> definitions.apply(definition, false)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(code));
        assertThat(TenantContext.current()).isNull();
    }
    @Test @AuditCovers(AuditAction.CLUB_UPDATED)
    void T_02_12_canicMatchesBrandingAndParametersFixturesAndSecondApplyDoesNotWrite() throws Exception {
        var definition = seed("canic");
        var preview = definitions.apply(Path.of("seeds/club-canic.yaml"), true);
        assertThat(preview.changes()).isPositive(); assertThat(preview.render(true)).contains("+ club", "+ domains", "+ theme", "+ admins", "dry run");
        for (String collection : List.of("clubs", "parameters", "accounts", "memberships", "domain_events", "audit_entries", "club_pages")) { assertThat(count(collection)).isZero(); }
        var first = definitions.apply(definition, false);
        var original = clubs.findBySlug("canic").orElseThrow();
        var response = mvc.perform(get("/api/v1/branding").header("Host", "app.agilitycanic.cat")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(mapper.readTree(response.getContentAsString())).isEqualTo(mapper.readTree(Path.of("src/test/resources/fixtures/branding-canic.json").toFile()));
        assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(configs.get(first.id()).parameters())).isEqualTo(mapper.readTree(Path.of("src/test/resources/fixtures/parameters-canic.json").toFile()));
        assertThat(count("domain_events")).isEqualTo(34); assertThat(count("audit_entries")).isEqualTo(4);
        assertThat(mongo.findAll(AuditEntry.class).stream().filter(a -> a.action() == AuditAction.CLUB_UPDATED).findFirst().orElseThrow().action()).isEqualTo(AuditAction.CLUB_UPDATED);
        assertThat(mongo.findAll(AuditEntry.class).stream().filter(a -> a.action() == AuditAction.CLUB_UPDATED).findFirst().orElseThrow().clubId()).isEqualTo(first.id());
        assertThat(mongo.findAll(AuditEntry.class).stream().filter(a -> a.action() == AuditAction.CLUB_UPDATED).findFirst().orElseThrow().reason()).isEqualTo("source: APPLY");
        assertThat(mongo.findAll(Account.class).getFirst().passwordHash()).startsWith("$argon2id$");
        var second = definitions.apply(definition, false);
        assertThat(second.changes()).isZero(); assertThat(second.render(false)).contains("0 changes");
        assertThat(clubs.findBySlug("canic").orElseThrow()).isEqualTo(original);
        assertThat(count("domain_events")).isEqualTo(34); assertThat(count("audit_entries")).isEqualTo(4);
        assertThat(count("accounts")).isEqualTo(15); assertThat(count("memberships")).isEqualTo(15);
        var exported = definitions.export("canic"); codec.validate(exported);
        assertThat(definitions.apply(exported, false).changes()).isZero();
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_17_01_parameterUpdatesPreserveUnlistedAndScopedOverridesAndRecordEventsHistory() {
        var definition = seed("canic"); definition.withObject("parameters").put("bookings.maxCurrentWeek", 3).put("signup.enabled", false);
        var first = definitions.apply(definition, false);
        try (var scope = TenantContext.open(first.id())) {
            parameters.insert(new Parameter("scoped", first.id(), "training.capacityPerRingSlot", 2, "int", "ring", "ring-a", List.of(), null, clock.instant()));
        }
        definition.withObject("parameters").remove("signup.enabled"); definition.withObject("parameters").put("bookings.maxCurrentWeek", 4);
        definition.withObject("club").put("name", "Updated club");
        definition.withObject("theme").withObject("colors").put("primary", "#123456");
        var preview = definitions.apply(definition, true);
        assertThat(preview.render(true)).contains("~ club", "club.name", "~ theme", "parameters.bookings.maxCurrentWeek", "3 -> 4");
        assertThat(configs.get(first.id()).get("bookings.maxCurrentWeek", Integer.class)).isEqualTo(3);
        var changed = definitions.apply(definition, false); assertThat(changed.changes()).isPositive();
        assertThat(configs.get(first.id()).get("bookings.maxCurrentWeek", Integer.class)).isEqualTo(4);
        assertThat(configs.get(first.id()).get("signup.enabled", Boolean.class)).isFalse();
        try (var scope = TenantContext.open(first.id())) {
            assertThat(parameters.findById("scoped")).isPresent();
            var saved = parameters.findAll().stream().filter(p -> p.key().equals("bookings.maxCurrentWeek")).findFirst().orElseThrow();
            assertThat(saved.history()).hasSize(2); assertThat(saved.history().getLast().value()).isEqualTo(3);
        }
        assertThat(mongo.findAll(org.bson.Document.class, "domain_events").stream().map(event -> event.getString("type")))
                .containsOnly("ClubUpdated", "ParameterChanged", "AccountCreated", "MembershipChanged", "ClubPageChanged");
        long events = count("domain_events"), auditCount = count("audit_entries");
        assertThat(definitions.apply(definition, false).changes()).isZero();
        assertThat(count("domain_events")).isEqualTo(events); assertThat(count("audit_entries")).isEqualTo(auditCount);
        var invalid = definition.deepCopy(); invalid.withObject("parameters").put("invented.key", true); failure(invalid, ErrorCode.UNKNOWN_PARAMETER);
        invalid = definition.deepCopy(); invalid.withObject("parameters").put("bookings.maxCurrentWeek", "wrong"); failure(invalid, ErrorCode.PARAMETER_INVALID);
    }
    @Test void T_17_02_hostConflictIsRejectedForVerifiedAndPendingHostsAndTenantsHaveDifferentThemes() throws Exception {
        var first = definitions.apply(seed("canic"), false);
        var conflict = seed("canic"); conflict.withObject("club").put("slug", "another"); failure(conflict, ErrorCode.HOST_ALREADY_USED);
        assertThat(count("clubs")).isEqualTo(1);
        var second = definitions.apply(seed("minim"), false);
        assertThat(hosts.resolve("minim.example.test")).contains(second.id());
        assertThat(configs.get(first.id()).club().theme()).isNotEqualTo(configs.get(second.id()).club().theme());
        mvc.perform(get("/api/v1/branding").header("Host", "minim.example.test")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.club.city").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v1/branding").header("Host", "absent.example.test")).andExpect(status().isNotFound());
        try (var scope = TenantContext.open(first.id())) { assertThat(parameters.findAll()).isEmpty(); }
        try (var scope = TenantContext.open(second.id())) { assertThat(parameters.findAll()).hasSize(1); }
        mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(first.id())),
                new org.springframework.data.mongodb.core.query.Update().set("domains.0.status", "PENDING"), Club.class);
        failure(conflict, ErrorCode.HOST_ALREADY_USED);
    }
    @Test void T_17_01_auditFailureRollsBackClubParametersAdminsAndOutboxTogether() {
        var definition = seed("minim"); doThrow(new IllegalStateException("injected audit failure")).when(audits).append(any());
        assertThatThrownBy(() -> definitions.apply(definition, false)).isInstanceOf(IllegalStateException.class);
        for (String collection : List.of("clubs", "parameters", "accounts", "memberships", "domain_events", "audit_entries", "club_pages")) { assertThat(count(collection)).as(collection).isZero(); }
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_17_01_templatePartialDefinitionAndExistingAdminPreserveIdentity() {
        var template = definitions.apply(seed("template-default"), false);
        var club = clubs.findBySlug("club-template-default").orElseThrow(); assertThat(club.template()).isTrue(); assertThat(club.domains()).isEmpty();
        assertThat(definitions.apply(definitions.export("club-template-default"), false).changes()).isZero();
        var definition = seed("canic"); definition.remove("accounts");
        definition.putArray("admins").addObject().put("email", "canic.admin@example.test").put("name", "Laia Example").put("locale", "ca");
        var result = definitions.apply(definition, false);
        var account = accounts.findByEmail("canic.admin@example.test").orElseThrow();
        try (var scope = TenantContext.open(result.id())) {
            var membership = memberships.findByAccountId(account.id()).orElseThrow();
            memberships.replace(new Membership(membership.id(), account.id(), result.id(), "member-a", Set.of(Role.MEMBER), Membership.Status.ACTIVE, Role.MEMBER));
        }
        ((ObjectNode) definition.path("admins").get(0)).put("name", "Ignored rename");
        definitions.apply(definition, false);
        assertThat(accounts.findById(account.id()).orElseThrow()).isEqualTo(account);
        try (var scope = TenantContext.open(result.id())) {
            assertThat(memberships.findByAccountId(account.id()).orElseThrow().roles()).containsExactlyInAnyOrder(Role.MEMBER, Role.ADMIN);
            admins.provision(new ClubAdminProvisioner.Admin(account.email(), account.name(), account.locale()));
            assertThat(admins.list()).hasSize(1);
        }
        definition.remove(List.of("domains", "paymentProviders", "legal", "admins"));
        assertThat(definitions.apply(definition, false).changes()).isZero();
        assertThat(clubs.findBySlug("canic").orElseThrow().domains()).hasSize(2);
        assertThatThrownBy(() -> definitions.export("missing")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.CLUB_NOT_FOUND));
        try (var scope = TenantContext.open("wrong")) {
            assertThatThrownBy(() -> writer.preview(definition)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo(ErrorCode.STALE_VERSION));
        }
        var deployed = mapping.merge(seed("minim"), null, "pending", clock.instant(), false);
        assertThat(deployed.domains().getFirst().status()).isEqualTo(Club.DomainStatus.PENDING);
        assertThat(deployed.domains().getFirst().verifiedAt()).isNull();
        var merged = mapping.merge(seed("minim"), deployed, "pending", clock.instant(), false);
        assertThat(merged.domains()).isEqualTo(deployed.domains());
    }
    @Test void T_17_01_cliExportsYamlAndExitsWithoutOpeningTheConfiguredHttpPort(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        definitions.apply(seed("canic"), false);
        try (var occupied = new java.net.ServerSocket(0)) {
            var output = temporary.resolve("export.yaml");
            var process = new ProcessBuilder("bin/core", "club:export", "canic");
            process.environment().put("SPRING_PROFILES_ACTIVE", "test");
            process.environment().put("MONGODB_TEST_URI", MONGO.getReplicaSetUrl("agilityhub_test"));
            process.environment().put("SERVER_PORT", Integer.toString(occupied.getLocalPort()));
            process.redirectOutput(output.toFile()); process.redirectError(temporary.resolve("stderr.txt").toFile());
            var child = process.start();
            try {
                assertThat(child.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(child.exitValue()).as(java.nio.file.Files.readString(temporary.resolve("stderr.txt"))).isZero();
                assertThat(codec.read(output)).isEqualTo(codec.validate(definitions.export("canic")));
                assertThat(java.nio.file.Files.readString(output)).doesNotContain("Tomcat", "Spring Boot");
            } finally { if (child.isAlive()) { child.destroyForcibly(); } }
        }
    }

}
