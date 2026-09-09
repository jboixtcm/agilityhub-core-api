package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.PasswordHasher;
import com.agilityhub.core.identity.application.SeedTestAccountsCommand;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.definition.ClubDefinitionCodec;
import com.agilityhub.core.platform.application.definition.ClubDefinitions;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = "identity.seed-password=Fictional-seed-password")
class SeedTestAccountsIT extends AbstractIntegrationTest {
    @Autowired ClubDefinitions definitions;
    @Autowired ClubDefinitionCodec codec;
    @Autowired ClubRepository clubs;
    @Autowired AccountRepository accounts;
    @Autowired MembershipRepository memberships;
    @Autowired SeedTestAccountsCommand command;
    @Autowired PasswordHasher passwords;
    @Autowired MongoTemplate mongo;
    @Autowired HostTenantResolver hosts;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach void clearSeeds() {
        for (String collection : List.of("clubs", "parameters", "accounts", "memberships", "refresh_tokens", "domain_events", "audit_entries")) {
            mongo.remove(new Query(), collection);
        }
        hosts.invalidate();
    }
    ObjectNode seed(String name) { return codec.read(Path.of("seeds/club-" + name + ".yaml")); }
    List<org.bson.Document> documents(String collection) { return mongo.findAll(org.bson.Document.class, collection); }

    @Test void T_01_02_seedRolesAndCredentialsAreIdempotentAndTenantBound() throws Exception {
        var input = seed("canic");
        var preview = definitions.apply(input, true);
        assertThat(preview.render(true)).contains("accounts: 15 to provision").doesNotContain("Fictional-seed-password", "passwordHash");
        for (String collection : List.of("clubs", "accounts", "memberships", "audit_entries", "domain_events")) {
            assertThat(documents(collection)).isEmpty();
        }
        var first = definitions.apply(input, false);
        var storedAccounts = documents("accounts"); var storedMemberships = documents("memberships");
        var storedEvents = documents("domain_events"); var storedAudits = documents("audit_entries");
        assertThat(definitions.apply(input, false).changes()).isZero();
        assertThat(documents("accounts")).isEqualTo(storedAccounts);
        assertThat(documents("memberships")).isEqualTo(storedMemberships);
        assertThat(documents("domain_events")).isEqualTo(storedEvents);
        assertThat(documents("audit_entries")).isEqualTo(storedAudits);
        assertThat(storedAccounts).hasSize(15);
        try (var scope = TenantContext.open(first.id())) {
            assertThat(memberships.findAll().stream().filter(m -> m.roles().equals(Set.of(Role.ADMIN)))) .hasSize(2);
            assertThat(memberships.findAll().stream().filter(m -> m.roles().equals(Set.of(Role.INSTRUCTOR)))) .hasSize(3);
            assertThat(memberships.findAll().stream().filter(m -> m.roles().equals(Set.of(Role.MEMBER)))) .hasSize(10);
            for (var membership : memberships.findAll()) {
                var account = accounts.findById(membership.accountId()).orElseThrow();
                assertThat(passwords.verify("Fictional-seed-password", account.passwordHash())).isTrue();
                assertThat(account.createdSource()).isEqualTo(Account.Source.CONSOLE);
                assertThat(account.platformRoles()).isEmpty(); assertThat(account.onboardingPending()).isFalse();
                assertThat(membership.memberId()).isNull(); assertThat(membership.instructorId()).isNull();
            }
        }
        var minimal = definitions.apply(seed("minim"), false);
        assertThat(definitions.apply(seed("minim"), false).changes()).isZero();
        try (var scope = TenantContext.open(minimal.id())) { assertThat(memberships.findAll()).hasSize(3); }
        var response = mvc.perform(post("/oauth2/token").header("Host", "app.agilitycanic.cat")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "password").param("client_id", "clubs-app")
                .param("username", "admin@example.test").param("password", "Fictional-seed-password"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String token = mapper.readTree(response.getContentAsString()).path("access_token").asText();
        mvc.perform(get("/api/v1/me").header("Host", "app.agilitycanic.cat").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.membership.roles").value(org.hamcrest.Matchers.contains("ADMIN")));
        mvc.perform(post("/oauth2/token").header("Host", "minim.example.test")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "password").param("client_id", "clubs-app")
                .param("username", "admin@example.test").param("password", "Fictional-seed-password"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_MEMBERSHIP"));
        mvc.perform(get("/api/v1/me").header("Host", "minim.example.test").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        var exported = definitions.export("canic"); codec.validate(exported);
        assertThat(exported.path("accounts")).hasSize(15);
        assertThat(exported.toString()).doesNotContain("password", "$argon2", "security", "Fictional-seed-password");
        assertThat(definitions.apply(exported, false).changes()).isZero();
    }
    @Test void T_17_01_aliasAppliesOnlyAccountsAndSupportsDryRun(@TempDir Path temporary) throws Exception {
        var definition = seed("canic"); definition.remove("accounts");
        var applied = definitions.apply(definition, false);
        var original = clubs.findBySlug("canic").orElseThrow();
        var input = seed("canic"); input.withObject("club").put("name", "Must not replace club name");
        input.withObject("parameters").put("signup.enabled", false);
        var file = temporary.resolve("accounts.yaml"); Files.writeString(file, codec.write(input));
        command.run(new DefaultApplicationArguments("--club=canic", "--dry-run"));
        assertThat(documents("accounts")).isEmpty();
        command.run(new DefaultApplicationArguments(file.toString()));
        assertThat(documents("accounts")).hasSize(15);
        var after = clubs.findBySlug("canic").orElseThrow();
        assertThat(after.name()).isEqualTo(original.name()); assertThat(after.theme()).isEqualTo(original.theme());
        assertThat(documents("parameters")).isEmpty();
        command.run(new DefaultApplicationArguments("--club=canic"));
        assertThat(clubs.findBySlug("canic").orElseThrow()).isEqualTo(after);
        assertThat(definitions.applyAccounts(file, true, false).changes()).isZero();
        assertThat(TenantContext.current()).isNull();
        input.withObject("club").put("slug", "absent"); Files.writeString(file, codec.write(input));
        assertThatThrownBy(() -> definitions.applyAccounts(file, false, false)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.CLUB_NOT_FOUND));
        assertThat(clubs.findBySlug("absent")).isEmpty();
    }
    @Test void T_01_02_existingGlobalAccountAndMemberLinksSurviveExactRoleUpdates() {
        var first = definitions.apply(seed("canic"), false);
        var account = accounts.findByEmail("admin@example.test").orElseThrow();
        var minimal = seed("minim");
        var entry = (ObjectNode) minimal.path("accounts").get(0);
        entry.put("email", "ADMIN@example.test").put("name", "Ignored rename").put("locale", "es")
                .put("onboardingPending", true).put("password", "Different-temporary-password");
        var second = definitions.apply(minimal, false);
        assertThat(accounts.findById(account.id()).orElseThrow()).isEqualTo(account);
        try (var scope = TenantContext.open(second.id())) {
            var membership = memberships.findByAccountId(account.id()).orElseThrow();
            memberships.replace(new Membership(membership.id(), account.id(), second.id(), "member-link", Set.of(Role.ADMIN, Role.MEMBER),
                    Membership.Status.SUSPENDED, Role.ADMIN, true, "instructor-link", membership.createdAt(), null));
        }
        entry.putArray("roles").add("INSTRUCTOR"); definitions.apply(minimal, false);
        try (var scope = TenantContext.open(second.id())) {
            var membership = memberships.findByAccountId(account.id()).orElseThrow();
            assertThat(membership.roles()).containsExactly(Role.INSTRUCTOR);
            assertThat(membership.status()).isEqualTo(Membership.Status.SUSPENDED);
            assertThat(membership.memberId()).isEqualTo("member-link"); assertThat(membership.instructorId()).isEqualTo("instructor-link");
            assertThat(membership.defaultProfile()).isNull(); assertThat(membership.rememberProfile()).isFalse();
        }
        try (var scope = TenantContext.open(first.id())) { assertThat(memberships.findByAccountId(account.id()).orElseThrow().roles()).containsExactly(Role.ADMIN); }
        assertThat(accounts.findById(account.id()).orElseThrow()).isEqualTo(account);
    }
    @Test void T_01_02_passwordlessSeedInitializesOnboardingWithoutConsentOrCredentialReset() {
        var input = seed("minim");
        input.path("accounts").forEach(account -> { ((ObjectNode) account).remove("password"); ((ObjectNode) account).put("onboardingPending", true); });
        definitions.apply(input, false);
        var account = accounts.findByEmail("minim.admin@example.test").orElseThrow();
        assertThat(account.passwordHash()).isNull(); assertThat(account.onboardingPending()).isTrue();
        assertThat(account.consents()).isEmpty(); assertThat(account.emailVerifiedAt()).isNull();
        accounts.completeOnboarding(account.id(), null);
        assertThat(definitions.apply(input, false).changes()).isZero();
        assertThat(accounts.findById(account.id()).orElseThrow().onboardingPending()).isFalse();
    }
}
