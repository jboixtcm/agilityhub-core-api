package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.AccountService;
import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.identity.application.ImpersonationService;
import com.agilityhub.core.identity.application.OnboardingService;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.Parameter;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.persistence.DomainEventRecord;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OnboardingIT extends IdentityIntegrationSupport {
    static final String PATH = "/api/v1/me/onboarding";
    @Autowired AccountService profileService;
    @Autowired OnboardingService onboarding;
    @Autowired IdentityTransactions transactions;
    @Autowired ImpersonationService impersonations;
    @Autowired ConfigurableEnvironment environment;

    @ParameterizedTest @ValueSource(strings = {"ca", "es", "en", "fr", "de", "no", "pt"})
    void T_01_23_onboardingAcceptsEveryProductLocale(String locale) throws Exception {
        call(put(PATH).contentType("application/json").content(mapper.writeValueAsString(Map.of(
                "consentAccepted", true, "consentVersion", "platform-v1", "fields", Map.of("locale", locale)))), "club-a", "MEMBER")
                .andExpect(status().isOk());
        assertThat(accounts.findById("account-a").orElseThrow().locale()).isEqualTo(locale);
    }

    @BeforeEach void onboardingFixture() {
        platformVersion("platform-v1");
        for (String collection : List.of("members", "audit_entries", "impersonation_grants")) { mongo.remove(new Query(), collection); }
        mongo.updateFirst(accountQuery(), new Update().set("onboardingPending", true).set("createdSource", "IMPORT_LEARN"), Account.class);
        member("member-a", "club-a", "account-a");
    }
    @AfterEach void restoreLegalConfiguration() { environment.getPropertySources().remove("onboarding-test"); }
    void platformVersion(String version) {
        environment.getPropertySources().addFirst(new MapPropertySource("onboarding-test", Map.of(
                "agilityhub.legal.privacyPolicyVersion", version, "agilityhub.legal.privacyPolicyUrl", "https://id.example.test/privacy")));
    }
    void clubVersion(String club, String version) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(club)), new Update().set("legal.legalTextsVersion", version), Club.class);
    }
    Query accountQuery() { return Query.query(Criteria.where("_id").is("account-a")); }
    void member(String id, String club, String account) {
        mongo.getCollection("members").insertOne(new Document("_id", id).append("clubId", club).append("accountId", account)
                .append("status", "ACTIVE").append("internalNotes", "Fictional census value to preserve").append("version", 7L)
                .append("phones", List.of(new Document("prefix", "+34").append("number", "600000001").append("label", "Primary"),
                        new Document("prefix", "+34").append("number", "600000002").append("label", "Secondary")))
                .append("consents", new Document("privacyPolicy", new Document("version", "legacy")).append("imageRights",
                        new Document("granted", false))));
    }
    ResultActions call(MockHttpServletRequestBuilder request, String club, String role) throws Exception {
        return mvc.perform(request.header("Host", club == null ? "id.example.test" : club.equals("club-a") ? HOST : "b.example.test")
                .with(jwt().jwt(j -> { j.subject("account-a"); if (club != null) { j.claim("clubId", club); } })
                        .authorities(() -> "ROLE_" + role)));
    }
    JsonNode state(String club) throws Exception { return json(call(get(PATH), club, "MEMBER")); }
    JsonNode json(ResultActions action) throws Exception {
        return mapper.readTree(action.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    MockHttpServletRequestBuilder acceptance(String version) {
        return put(PATH).contentType("application/json").content("{\"consentAccepted\":true,\"consentVersion\":\"" + version + "\"}");
    }
    JsonNode accept(String club, String version) throws Exception { return json(call(acceptance(version), club, "MEMBER")); }
    List<AuditEntry> audits() { return mongo.find(Query.query(Criteria.where("action").is("ONBOARDING_COMPLETED")), AuditEntry.class); }

    @Test @AuditCovers(AuditAction.ONBOARDING_COMPLETED)
    void T_01_26_importedAccountAcceptsOnceWithVersionTimeAndTransactionalAudit() throws Exception {
        var initial = state(null);
        assertThat(initial.path("pending").asBoolean()).isTrue();
        assertThat(initial.at("/requiredConsent/policy").asText()).isEqualTo("PLATFORM");
        assertThat(initial.at("/requiredConsent/url").asText()).isEqualTo("https://id.example.test/privacy");
        assertThat(initial.path("postponeRemaining").asInt()).isZero();
        assertThat(json(call(post(PATH + "/postpone"), null, "MEMBER"))).isEqualTo(initial);
        call(get("/api/v1/me"), null, "MEMBER").andExpect(jsonPath("$.account.onboardingPending").value(true));
        System.out.println("T-01-26 example GET /me/onboarding: " + initial);
        var completed = accept(null, "platform-v1");
        assertThat(completed.path("pending").asBoolean()).isFalse();
        assertThat(completed.path("requiredConsent").isNull()).isTrue();
        assertThat(accept(null, "platform-v1")).isEqualTo(completed);
        assertThat(json(call(post(PATH + "/postpone"), null, "MEMBER"))).isEqualTo(completed);
        var saved = accounts.findById("account-a").orElseThrow();
        assertThat(saved.onboardingPending()).isFalse();
        assertThat(saved.consents()).containsExactly(new Account.Consent(Account.ConsentPolicy.PLATFORM, null, "platform-v1", clock.instant()));
        call(get("/api/v1/me"), null, "MEMBER").andExpect(jsonPath("$.account.onboardingPending").value(false));
        assertThat(audits()).singleElement().satisfies(audit -> {
            assertThat(audit.entityType()).isEqualTo("Account"); assertThat(audit.entityId()).isEqualTo("account-a");
            assertThat(audit.actorAccountId()).isEqualTo("account-a"); assertThat(audit.clubId()).isNull();
            assertThat(audit.at()).isEqualTo(clock.instant());
            assertThat(audit.changes()).extracting(change -> change.path()).contains("consents", "onboardingPending");
        });
        assertThat(mapper.writeValueAsString(completed)).doesNotContain("passwordHash", "security", "consents", "clubId");
    }

    @Test void T_01_26_platformThenClubConsentAndMemberFieldsPreserveOtherCensusData() throws Exception {
        var initial = state("club-a");
        assertThat(initial.at("/fields/2/value").asText()).isEqualTo("+34600000001");
        assertThat(initial.at("/fields/0/required").asBoolean()).isTrue();
        var next = json(call(acceptance("platform-v1").content("""
                {"consentAccepted":true,"consentVersion":"platform-v1","fields":{"name":" Updated Example ","locale":"es","phone":" +34600000003 "},"imageConsent":true}
                """), "club-a", "MEMBER"));
        assertThat(next.path("pending").asBoolean()).isTrue();
        assertThat(next.at("/requiredConsent/policy").asText()).isEqualTo("CLUB");
        assertThat(next.at("/requiredConsent/version").asText()).isEqualTo("v1");
        assertThat(next.at("/fields/0/value").asText()).isEqualTo("Updated Example");
        assertThat(next.at("/fields/1/value").asText()).isEqualTo("es");
        assertThat(next.at("/fields/2/value").asText()).isEqualTo("+34600000003");
        var member = mongo.getCollection("members").find(new Document("_id", "member-a")).first();
        assertThat(member.getString("internalNotes")).isEqualTo("Fictional census value to preserve");
        assertThat(member.getList("phones", Document.class)).hasSize(2);
        assertThat(member.getList("phones", Document.class).get(1).getString("number")).isEqualTo("600000002");
        assertThat(member.getList("phones", Document.class).getFirst().getString("label")).isEqualTo("Primary");
        assertThat(member.get("consents", Document.class).get("privacyPolicy", Document.class).getString("version")).isEqualTo("legacy");
        var image = member.get("consents", Document.class).get("imageRights", Document.class);
        assertThat(image.getBoolean("granted")).isTrue(); assertThat(image.getString("version")).isEqualTo("v1");
        assertThat(image.getString("byAccountId")).isEqualTo("account-a"); assertThat(image.getDate("at").toInstant()).isEqualTo(clock.instant());
        assertThat(member.getLong("version")).isEqualTo(8L);
        assertThat(accept("club-a", "v1").path("pending").asBoolean()).isFalse();
        assertThat(audits()).hasSize(2);
        assertThat(accounts.findById("account-a").orElseThrow().consents()).hasSize(2);
        assertThat(mongo.count(Query.query(Criteria.where("type").is("AccountLocaleChanged")), DomainEventRecord.class)).isEqualTo(1);
    }

    @Test void T_01_26_policyBumpsResetBoundedRenewalAllowanceWithoutCachedVersion() throws Exception {
        accept("club-a", "platform-v1"); accept("club-a", "v1");
        platformVersion("platform-v2");
        assertThat(state("club-a").path("postponeRemaining").asInt()).isEqualTo(3);
        for (int remaining : new int[]{2, 1, 0, 0}) {
            var postponed = json(call(post(PATH + "/postpone"), "club-a", "MEMBER"));
            assertThat(postponed.path("pending").asBoolean()).isTrue();
            assertThat(postponed.path("postponeRemaining").asInt()).isEqualTo(remaining);
        }
        call(acceptance("platform-v1"), "club-a", "MEMBER").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONSENT_VERSION_OUTDATED"));
        assertThat(accept("club-a", "platform-v2").path("pending").asBoolean()).isFalse();
        clubVersion("club-a", "v2");
        assertThat(state("club-a").at("/requiredConsent/version").asText()).isEqualTo("v2");
        assertThat(state("club-a").path("postponeRemaining").asInt()).isEqualTo(3);
        call(acceptance("v1"), "club-a", "MEMBER").andExpect(status().isUnprocessableEntity());
        assertThat(accept("club-a", "v2").path("pending").asBoolean()).isFalse();
        platformVersion("platform-v3");
        assertThat(state("club-a").path("postponeRemaining").asInt()).isEqualTo(3);
    }

    @Test void T_01_26_sameVersionAtDifferentClubsDoesNotShareConsentsOrPostponements() throws Exception {
        membership("club-b", Set.of(Role.MEMBER), Role.MEMBER, "member-b"); member("member-b", "club-b", "account-a");
        accept("club-a", "platform-v1"); accept("club-a", "v1");
        assertThat(state("club-b").at("/requiredConsent/policy").asText()).isEqualTo("CLUB");
        assertThat(state("club-b").path("postponeRemaining").asInt()).isZero();
        accept("club-b", "v1");
        assertThat(mapper.writeValueAsString(audits().stream().filter(audit -> "club-b".equals(audit.clubId())).toList()))
                .doesNotContain("club-a");
        clubVersion("club-a", "v2"); clubVersion("club-b", "v2");
        json(call(post(PATH + "/postpone"), "club-a", "MEMBER"));
        assertThat(state("club-a").path("postponeRemaining").asInt()).isEqualTo(2);
        assertThat(state("club-b").path("postponeRemaining").asInt()).isEqualTo(3);
        accept("club-a", "v2");
        assertThat(state("club-b").path("pending").asBoolean()).isTrue();
        assertThat(state(null).path("pending").asBoolean()).isFalse();
    }

    @Test void T_01_26_catalogFieldAndPostponementOverridesAreApplied() throws Exception {
        mongo.insert(new Parameter("onboarding-fields", "club-a", "signup.onboardingFields", List.of(Map.of("key", "phone", "required", true)),
                "json", "club", null, List.of(), null, clock.instant()));
        mongo.insert(new Parameter("onboarding-postpones", "club-a", "legal.maxPostpones", 1, "int", "club", null, List.of(), null, clock.instant()));
        configs.invalidate("club-a");
        var fields = state("club-a").path("fields"); assertThat(fields).hasSize(1);
        assertThat(fields.get(0).get("key").asText()).isEqualTo("phone"); assertThat(fields.get(0).get("required").asBoolean()).isTrue();
        accept("club-a", "platform-v1"); accept("club-a", "v1"); platformVersion("platform-v2");
        assertThat(state("club-a").path("postponeRemaining").asInt()).isEqualTo(1);
        assertThat(json(call(post(PATH + "/postpone"), "club-a", "MEMBER")).path("postponeRemaining").asInt()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN", "GUEST"})
    void T_01_17_allAccountRolesCanUseEveryOnboardingEndpointInGlobalAndOwnTenantContexts(String role) throws Exception {
        for (String club : new String[]{null, "club-a"}) {
            call(get(PATH), club, role).andExpect(status().isOk());
            call(post(PATH + "/postpone"), club, role).andExpect(status().isOk());
            call(acceptance(club == null ? "platform-v1" : "v1"), club, role).andExpect(status().isOk());
        }
    }

    @Test void T_01_17_allOnboardingEndpointsEnforceAuthenticationHostMembershipAndAccountState() throws Exception {
        for (var request : List.of(get(PATH), acceptance("platform-v1"), post(PATH + "/postpone"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
            mvc.perform(request.header("Host", "b.example.test").with(jwt().jwt(j -> j.subject("account-a").claim("clubId", "club-a"))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
        }
        for (var request : List.of(get(PATH), acceptance("platform-v1"), post(PATH + "/postpone"))) {
            call(request, "club-b", "MEMBER").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_MEMBERSHIP"));
        }
        mongo.updateFirst(new Query(), new Update().set("status", Membership.Status.SUSPENDED), Membership.class);
        for (var request : List.of(get(PATH), acceptance("platform-v1"), post(PATH + "/postpone"))) {
            call(request, "club-a", "MEMBER").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBERSHIP_SUSPENDED"));
        }
        mongo.updateFirst(accountQuery(), new Update().set("status", Account.Status.BLOCKED), Account.class);
        for (var request : List.of(get(PATH), acceptance("platform-v1"), post(PATH + "/postpone"))) {
            call(request, null, "MEMBER").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
        }
    }

    @Test void T_01_26_impersonationCannotAcceptOrPostponeAnotherPersonsConsent() throws Exception {
        membership("club-a", Set.of(Role.MEMBER, Role.ADMIN), Role.ADMIN, "member-a");
        String token;
        try (var scope = TenantContext.open("club-a")) { token = impersonations.create("account-a", "member-a", "Fictional support").token().getTokenValue(); }
        for (var request : List.of(acceptance("platform-v1"), post(PATH + "/postpone"))) {
            mvc.perform(request.header("Host", HOST).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        assertThat(accounts.findById("account-a").orElseThrow().consents()).isEmpty();
    }

    @Test void T_01_26_invalidConsentAndProfileDataLeaveAllDocumentsUnchanged() throws Exception {
        for (String body : List.of("{}", "{\"consentAccepted\":false,\"consentVersion\":\"platform-v1\"}",
                "{\"consentAccepted\":true,\"consentVersion\":\"platform-v1\",\"fields\":{\"name\":\" \"}}",
                "{\"consentAccepted\":true,\"consentVersion\":\"platform-v1\",\"fields\":{\"phone\":\" \"}}")) {
            call(put(PATH).contentType("application/json").content(body), "club-a", "MEMBER")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        call(acceptance("platform-v1").content("{\"consentAccepted\":true,\"consentVersion\":\"platform-v1\",\"fields\":{\"locale\":\"xx\"}}"), "club-a", "MEMBER")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LOCALE_NOT_SUPPORTED"));
        call(acceptance("stale"), "club-a", "MEMBER").andExpect(status().isUnprocessableEntity());
        assertThat(accounts.findById("account-a").orElseThrow().consents()).isEmpty(); assertThat(audits()).isEmpty();
        assertThat(accounts.findById("account-a").orElseThrow().name()).isEqualTo("Example Admin");
        accept(null, "platform-v1");
        call(acceptance("stale"), null, "MEMBER").andExpect(status().isUnprocessableEntity());
    }

    @Test void T_01_26_memberUpdatesIgnoreMissingErasedForeignOrUnlinkedMembers() throws Exception {
        for (String mutation : List.of("missing", "foreign", "unlinked", "erased", "no-member")) {
            mongo.remove(new Query(), "members");
            member("member-a", mutation.equals("foreign") ? "club-b" : "club-a", mutation.equals("unlinked") ? "another-account" : "account-a");
            if (mutation.equals("missing")) { mongo.remove(new Query(), "members"); }
            if (mutation.equals("erased")) { mongo.updateFirst(new Query(), new Update().set("status", "ERASED"), "members"); }
            if (mutation.equals("no-member")) { membership("club-a", Set.of(Role.ADMIN), Role.ADMIN, null); }
            var before = mongo.getCollection("members").find().into(new ArrayList<>());
            assertThat(state("club-a").at("/fields/2/value").isNull()).isTrue();
            call(acceptance(mutation.equals("missing") ? "platform-v1" : "v1").content(mapper.writeValueAsString(Map.of(
                    "consentAccepted", true, "consentVersion", mutation.equals("missing") ? "platform-v1" : "v1",
                    "fields", Map.of("phone", "+34600000009"), "imageConsent", false))), "club-a", "MEMBER").andExpect(status().isOk());
            assertThat(mongo.getCollection("members").find().into(new ArrayList<>())).isEqualTo(before);
        }
    }

    @Test void T_01_26_accountMemberAuditAndLocaleEventRollBackTogether() {
        var before = mongo.getCollection("members").find().first();
        try (var scope = TenantContext.open("club-a")) {
            assertThatThrownBy(() -> transactions.run(() -> {
                onboarding.complete("account-a", true, "platform-v1", "Changed", "es", "+34600000009", true);
                throw new IllegalStateException("Forced rollback");
            })).isInstanceOf(IllegalStateException.class).hasMessage("Forced rollback");
        }
        assertThat(accounts.findById("account-a").orElseThrow().consents()).isEmpty();
        assertThat(accounts.findById("account-a").orElseThrow().name()).isEqualTo("Example Admin");
        assertThat(audits()).isEmpty(); assertThat(mongo.count(new Query(), DomainEventRecord.class)).isZero();
        assertThat(mongo.getCollection("members").find().first()).isEqualTo(before);
    }

    @Test void T_01_26_concurrentAcceptancesAndPostponementsDoNotDuplicateOrLoseUpdates() throws Exception {
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var accepts = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 4; i++) { accepts.add(() -> { accept(null, "platform-v1"); return null; }); }
            for (var result : executor.invokeAll(accepts)) { result.get(); }
            assertThat(accounts.findById("account-a").orElseThrow().consents()).hasSize(1); assertThat(audits()).hasSize(1);
            platformVersion("platform-v2");
            var postpones = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 4; i++) { postpones.add(() -> { json(call(post(PATH + "/postpone"), null, "MEMBER")); return null; }); }
            for (var result : executor.invokeAll(postpones)) { result.get(); }
        }
        assertThat(state(null).path("postponeRemaining").asInt()).isZero();
        assertThat(accounts.findById("account-a").orElseThrow().consentPostponements()).singleElement().satisfies(value -> assertThat(value.count()).isEqualTo(3));
    }

    @Test void T_01_26_importMigrationAndSignupFlagsAndMissingMemberFields() throws Exception {
        for (var source : List.of(Account.Source.IMPORT_LEARN, Account.Source.MIGRATION, Account.Source.SIGNUP)) {
            var account = profileService.getOrCreate(source.name() + "@example.test", "Fictional Profile", "en", source);
            assertThat(account.onboardingPending()).isEqualTo(source != Account.Source.SIGNUP);
            assertThat(account.consents()).isEmpty();
        }
        mongo.updateFirst(new Query(), new Update().unset("phones").set("consents", null), "members");
        assertThat(state("club-a").at("/fields/2/value").isNull()).isTrue();
        call(acceptance("platform-v1").content("{\"consentAccepted\":true,\"consentVersion\":\"platform-v1\",\"fields\":{\"phone\":\"600000004\"},\"imageConsent\":false}"), "club-a", "MEMBER")
                .andExpect(status().isOk());
        assertThat(state("club-a").at("/fields/2/value").asText()).isEqualTo("600000004");
        assertThat(mongo.getCollection("members").find().first().get("consents", Document.class)
                .get("imageRights", Document.class).getBoolean("granted")).isFalse();
        mongo.updateFirst(new Query(), new Update().set("phones", List.of()), "members");
        assertThat(state("club-a").at("/fields/2/value").isNull()).isTrue();
        mongo.updateFirst(new Query(), new Update().set("phones", List.of(new Document("number", "600000005"))), "members");
        assertThat(state("club-a").at("/fields/2/value").asText()).isEqualTo("600000005");
    }
}
