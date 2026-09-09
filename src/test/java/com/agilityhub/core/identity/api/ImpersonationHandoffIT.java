package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.*;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.SecurityEvent;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.persistence.DomainEventRecord;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(ImpersonationHandoffIT.ProbeConfiguration.class)
class ImpersonationHandoffIT extends IdentityIntegrationSupport {
    @Autowired HandoffService handoffs;
    @Autowired ImpersonationService impersonations;
    @Autowired IdentityTransactions transactions;
    @Autowired TokenService tokens;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    @org.springframework.beans.factory.annotation.Value("${identity.issuer}") String issuer;
    static final String ADMIN_HOST = "admin.example.test";

    @BeforeEach void grantsFixture() {
        for (String collection : List.of("members", "impersonation_grants", "handoff_codes", "audit_entries", "impersonation_probes")) {
            mongo.remove(new Query(), collection);
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is("club-a")), new Update().push("domains",
                new Document("host", ADMIN_HOST).append("app", "clubs-admin").append("status", "VERIFIED").append("primary", true)), Club.class);
        hosts.invalidate(); configs.invalidate("club-a");
        accounts.save(new Account("account-member", "member@example.test", "Example Member", "en", passwords.hash(PASSWORD),
                Set.of(Account.PlatformRole.AGILITYHUB_ADMIN), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        try (var scope = TenantContext.open("club-a")) {
            memberships.insert(new Membership("membership-member", "account-member", "club-a", "member-target",
                    Set.of(Role.MEMBER, Role.ADMIN, Role.INSTRUCTOR), Membership.Status.ACTIVE, Role.ADMIN));
        }
        member("member-target", "club-a", "account-member", "ACTIVE");
    }
    void member(String id, String club, String account, String status) {
        mongo.getCollection("members").insertOne(new Document("_id", id).append("clubId", club).append("accountId", account).append("status", status));
    }
    String bearer(JsonNode response) { return "Bearer " + response.path("access_token").asText(); }
    ResultActions create(String token, String member, String host) throws Exception {
        return mvc.perform(post("/api/v1/members/" + member + "/impersonation-token").header("Host", host).header("Authorization", token)
                .contentType("application/json").content("{\"reason\":\"Help with a booking\"}"));
    }
    JsonNode impersonate(String admin) throws Exception {
        return mapper.readTree(create(admin, "member-target", HOST).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    ResultActions requestHandoff(String bearer, String client, String host) throws Exception {
        return mvc.perform(post("/api/v1/auth/handoff").header("Host", host).header("Authorization", bearer)
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("targetClientId", client))));
    }
    JsonNode handoff(String bearer, String client) throws Exception {
        return mapper.readTree(requestHandoff(bearer, client, HOST).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    ResultActions exchange(String code, String client, String host) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Host", host).contentType("application/x-www-form-urlencoded")
                .param("grant_type", HandoffService.GRANT).param("client_id", client).param("token", code));
    }
    List<DomainEventRecord> events(String type) { return mongo.find(Query.query(Criteria.where("type").is(type)), DomainEventRecord.class); }
    void revoke(String caller, String value) throws Exception {
        mvc.perform(post("/oauth2/revoke").header("Host", HOST).header("Authorization", caller).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("token", value)))).andExpect(status().isOk());
    }

    @Test @AuditCovers(AuditAction.IMPERSONATION_STARTED)
    void T_01_11_claimsMemberViewAuditedMutationAndRevocation() throws Exception {
        String admin = bearer(login());
        long refreshBefore = mongo.count(new Query(), RefreshToken.class);
        var issued = impersonate(admin);
        String value = issued.path("token").asText();
        String imp = "Bearer " + value;
        var claims = SignedJWT.parse(value).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("account-member");
        assertThat(claims.getStringListClaim("roles")).containsExactly("MEMBER");
        assertThat(claims.getBooleanClaim("imp")).isTrue();
        assertThat(claims.getStringClaim("actorAccountId")).isEqualTo("account-a");
        assertThat(claims.getStringClaim("impersonatedMemberId")).isEqualTo("member-target");
        assertThat(claims.getClaim("platformRoles")).isNull();
        assertThat(claims.getClaim("sid")).isNull();
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(clock.instant().plusSeconds(3600));
        assertThat(issued.path("expiresAt").asText()).isEqualTo(clock.instant().plusSeconds(3600).toString());
        assertThat(issued.has("refresh_token")).isFalse();
        assertThat(mongo.count(new Query(), RefreshToken.class)).isEqualTo(refreshBefore);
        System.out.println("T-01-11 decoded fictional JWT claims: " + claims.toJSONObject());
        mvc.perform(get("/api/v1/me").header("Host", HOST).header("Authorization", imp)).andExpect(status().isOk())
                .andExpect(jsonPath("$.membership.roles[0]").value("MEMBER")).andExpect(jsonPath("$.membership.roles.length()").value(1))
                .andExpect(jsonPath("$.membership.profiles[0]").value("MEMBER")).andExpect(jsonPath("$.account.platformRoles").isEmpty())
                .andExpect(jsonPath("$.impersonation.actorName").value("Example Admin"));
        mvc.perform(post("/api/v1/test/impersonation-probe").header("Host", HOST).header("Authorization", imp))
                .andExpect(status().isOk()).andExpect(jsonPath("$.origin").value("BACKOFFICE"));
        var audit = mongo.find(Query.query(Criteria.where("action").is("PARAMETER_CHANGED")), AuditEntry.class).getFirst();
        assertThat(audit.actorAccountId()).isEqualTo("account-a");
        assertThat(audit.actorName()).isEqualTo("Example Admin"); assertThat(audit.actorRole()).isEqualTo("ADMIN");
        assertThat(audit.impersonatedMemberId()).isEqualTo("member-target");
        var started = mongo.find(Query.query(Criteria.where("action").is("IMPERSONATION_STARTED")), AuditEntry.class).getFirst();
        assertThat(started.actorAccountId()).isEqualTo("account-a"); assertThat(started.reason()).isEqualTo("Help with a booking");
        assertThat(started.memberId()).isEqualTo("member-target");
        assertThat(events("ImpersonationStarted")).singleElement().satisfies(e -> {
            assertThat(e.actorAccountId()).isEqualTo("account-a"); assertThat(e.impersonatedMemberId()).isEqualTo("member-target");
            assertThat(e.eventJson()).doesNotContain(value);
        });
        assertThat(CurrentUser.current()).isNull(); assertThat(TenantContext.current()).isNull();
        for (String path : List.of("/api/v1/members", "/api/v1/test/instructor-probe")) {
            mvc.perform(get(path).header("Host", HOST).header("Authorization", imp))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(get("/api/v1/platform/accounts/account-a/platform-roles").header("Host", HOST).header("Authorization", imp))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(put("/api/v1/platform/accounts/account-a/platform-roles").header("Host", HOST).header("Authorization", imp)
                .contentType("application/json").content("{\"platformRoles\":[\"AGILITYHUB_ADMIN\"]}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        create(imp, "member-target", HOST).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IMPERSONATION_DENIED"));
        requestHandoff(imp, "clubs-admin", HOST).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IMPERSONATION_DENIED"));
        mvc.perform(put("/api/v1/me/profile").header("Host", HOST).header("Authorization", imp).contentType("application/json")
                .content("{\"activeProfile\":\"ADMIN\",\"remember\":false}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/sessions").header("Host", HOST).header("Authorization", imp)).andExpect(status().isForbidden());
        refresh(value, HOST, "clubs-app").andExpect(status().isBadRequest());
        revoke(imp, value); revoke(imp, value); revoke(admin, value);
        assertThat(events("ImpersonationEnded")).hasSize(1);
        mvc.perform(get("/api/v1/me").header("Host", HOST).header("Authorization", imp)).andExpect(status().isUnauthorized());
        assertThat(mongo.findAll(ImpersonationGrant.class).getFirst().revokedAt()).isEqualTo(clock.instant());
    }

    @Test void T_01_11_tenantRolesTargetStateAndExpiryAreEnforced() throws Exception {
        String admin = bearer(login());
        create(admin, "member-target", "b.example.test").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
        member("member-foreign", "club-b", "account-member", "ACTIVE");
        create(admin, "member-foreign", HOST).andExpect(status().isNotFound());
        create(admin, "missing", HOST).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/members/member-target/impersonation-token").header("Host", HOST).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        for (String state : List.of("PENDING", "LEFT", "ERASED")) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is("member-target")), new Update().set("status", state), "members");
            create(admin, "member-target", HOST).andExpect(status().isForbidden());
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is("member-target")), new Update().set("status", "INACTIVE"), "members");
        var issued = impersonate(admin);
        String imp = "Bearer " + issued.path("token").asText();
        clock.advance(Duration.ofMinutes(60));
        mvc.perform(get("/api/v1/me").header("Host", HOST).header("Authorization", imp)).andExpect(status().isUnauthorized());
        clock.advance(Duration.ofMinutes(-60));
        for (String role : List.of("MEMBER", "INSTRUCTOR")) {
            membership("club-a", Set.of(Role.valueOf(role)), Role.valueOf(role), "member-a");
            String caller = bearer(login());
            create(caller, "member-target", HOST).andExpect(status().isForbidden());
        }
        assertThat(mongo.findAll(SecurityEvent.class)).anyMatch(e -> e.type().name().equals("IMPERSONATION_DENIED"));
        // The issued grant stops working when its actor loses ADMIN, even with a still-live JWT.
        mvc.perform(get("/api/v1/me").header("Host", HOST).header("Authorization", imp)).andExpect(status().isForbidden());
    }

    @Test void T_01_11_transactionRollbackRemovesGrantEventAndAudit() {
        try (var scope = TenantContext.open("club-a")) {
            assertThatThrownBy(() -> transactions.run(() -> {
                impersonations.create("account-a", "member-target", null);
                assertThat(mongo.findAll(AuditEntry.class)).hasSize(1);
                throw new IllegalStateException("rollback");
            })).hasMessage("rollback");
        }
        assertThat(mongo.findAll(ImpersonationGrant.class)).isEmpty();
        assertThat(mongo.findAll(AuditEntry.class)).isEmpty(); assertThat(events("ImpersonationStarted")).isEmpty();
    }

    @Test void T_01_12_handoffUsesDestinationHostAndClientAndIsSingleUse() throws Exception {
        var source = login(); String admin = bearer(source);
        var created = handoff(admin, "clubs-admin"); String code = created.path("code").asText();
        assertThat(created.path("url").asText()).isEqualTo("https://" + ADMIN_HOST + "/entrar?handoff=" + code);
        var stored = mongo.findAll(HandoffCode.class).getFirst();
        assertThat(stored.codeHash()).isEqualTo(TokenService.digest(code)); assertThat(stored.expiresAt()).isEqualTo(clock.instant().plusSeconds(60));
        assertThat(mongo.getCollection("handoff_codes").find().first().toJson()).doesNotContain(code);
        exchange(code, "clubs-app", HOST).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HANDOFF_INVALID"));
        exchange(code, "clubs-admin", "b.example.test").andExpect(status().isBadRequest());
        var result = readTokens(exchange(code, "clubs-admin", ADMIN_HOST).andExpect(status().isOk())
                .andExpect(cookie().exists(RefreshCookies.NAME)).andExpect(jsonPath("$.refresh_token").doesNotExist()).andReturn().getResponse());
        assertThat(SignedJWT.parse(result.path("access_token").asText()).getJWTClaimsSet().getAudience()).containsExactly("clubs-admin");
        assertThat(refreshValue(result)).isNotBlank();
        exchange(code, "clubs-admin", ADMIN_HOST).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HANDOFF_INVALID"));
        refresh(refreshValue(source), HOST, "clubs-admin").andExpect(status().isBadRequest());
        refresh(refreshValue(result), ADMIN_HOST, "clubs-app").andExpect(status().isBadRequest());
        refresh(refreshValue(result), ADMIN_HOST, "clubs-admin").andExpect(status().isOk());
        var back = handoff(bearer(result), "clubs-app");
        assertThat(back.path("url").asText()).startsWith("https://" + HOST + "/entrar?handoff=");
        exchange(back.path("code").asText(), "clubs-app", "id.agilitydoghub.com").andExpect(status().isOk());
        assertThat(mongo.findAll(SecurityEvent.class)).anyMatch(e -> e.type().name().equals("HANDOFF_INVALID"));
        assertThat(mongo.getCollection("security_events").find().into(new java.util.ArrayList<>()).toString()).doesNotContain(code, stored.codeHash());
    }

    @Test void T_01_12_expiryRolesMembershipAndSourceRevocationAreEnforced() throws Exception {
        var source = login(); String admin = bearer(source);
        mvc.perform(post("/api/v1/auth/handoff").header("Host", HOST).contentType("application/json").content("{\"targetClientId\":\"clubs-admin\"}"))
                .andExpect(status().isUnauthorized());
        requestHandoff(admin, "clubs-admin", "b.example.test").andExpect(status().isForbidden());
        requestHandoff(admin, "unknown", HOST).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_MEMBERSHIP"));
        String code = handoff(admin, "clubs-admin").path("code").asText();
        clock.advance(Duration.ofSeconds(60));
        exchange(code, "clubs-admin", HOST).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HANDOFF_INVALID"));
        code = handoff(admin, "clubs-admin").path("code").asText();
        membership("club-a", Set.of(Role.MEMBER), Role.MEMBER, "member-a");
        requestHandoff(admin, "clubs-admin", HOST).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_MEMBERSHIP"));
        exchange(code, "clubs-admin", HOST).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_MEMBERSHIP"));
        membership("club-a", Set.of(Role.ADMIN), Role.ADMIN, "member-a");
        revoke(admin, refreshValue(source));
        exchange(code, "clubs-admin", HOST).andExpect(status().isBadRequest());
        assertThat(mongo.findAll(HandoffCode.class)).allMatch(c -> c.usedAt() == null);
        var fresh = login(); code = handoff(bearer(fresh), "clubs-admin").path("code").asText();
        mongo.updateFirst(Query.query(Criteria.where("_id").is("membership-club-a")), new Update().set("status", "SUSPENDED"), Membership.class);
        exchange(code, "clubs-admin", HOST).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBERSHIP_SUSPENDED"));
    }

    @Test void T_01_12_concurrentRedemptionIssuesOnlyOneSessionAndRollbackPreservesCode() throws Exception {
        var source = login(); String code = handoff(bearer(source), "clubs-admin").path("code").asText();
        long before = mongo.count(new Query(), RefreshToken.class);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            Callable<String> redeem = () -> {
                try { handoffs.exchange(code, "clubs-admin", null); return "OK"; }
                catch (com.agilityhub.core.shared.domain.ApiException failure) { return failure.code().name(); }
            };
            var results = pool.invokeAll(List.of(redeem, redeem));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder("OK", "HANDOFF_INVALID");
        }
        assertThat(mongo.count(new Query(), RefreshToken.class)).isEqualTo(before + 1);
        String rollbackCode = handoff(bearer(source), "clubs-admin").path("code").asText();
        assertThatThrownBy(() -> transactions.run(() -> { handoffs.exchange(rollbackCode, "clubs-admin", null); throw new IllegalStateException("rollback"); }))
                .hasMessage("rollback");
        exchange(rollbackCode, "clubs-admin", HOST).andExpect(status().isOk());
    }

    @Test void T_01_11_targetLinkageRevocationOwnershipAndClaimIntegrity() throws Exception {
        String admin = bearer(login());
        String value = impersonate(admin).path("token").asText();
        var parsed = SignedJWT.parse(value).getJWTClaimsSet();
        for (String claim : List.of("actorAccountId", "impersonatedMemberId", "sub", "jti")) {
            var altered = new java.util.HashMap<String, Object>(parsed.getClaims());
            altered.put("iat", clock.instant()); altered.put("exp", clock.instant().plusSeconds(3600));
            altered.put(claim, "unrelated");
            if (claim.equals("sub")) { altered.put(claim, "account-a"); }
            String forged = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                    org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().claims(c -> c.putAll(altered)).build())).getTokenValue();
            mvc.perform(get("/api/v1/me").header("Host", HOST).header("Authorization", "Bearer " + forged))
                    .andExpect(status().isUnauthorized());
        }
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer(issuer).subject("unrelated")
                .audience(List.of("clubs-app")).issuedAt(clock.instant()).expiresAt(clock.instant().plusSeconds(900))
                .claim("clubId", "club-a").claim("roles", List.of("MEMBER")).build();
        String stranger = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(claims)).getTokenValue();
        revoke("Bearer " + stranger, value);
        assertThat(mongo.findAll(ImpersonationGrant.class).getFirst().revokedAt()).isNull();
        mvc.perform(post("/oauth2/revoke").header("Host", HOST).header("Authorization", "Bearer " + value).contentType("application/json")
                .content("{\"token\":\"someone-elses-session\"}")).andExpect(status().isForbidden());
        revoke(admin, "malformed.jwt.value");
        revoke(admin, admin.substring(7));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("membership-member")), new Update().set("memberId", "different"), Membership.class);
        create(admin, "member-target", HOST).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me").header("Host", HOST).header("Authorization", "Bearer " + value)).andExpect(status().isForbidden());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("membership-member")), new Update().set("memberId", "member-target").set("roles", List.of("INSTRUCTOR")).set("defaultProfile", "INSTRUCTOR"), Membership.class);
        create(admin, "member-target", HOST).andExpect(status().isForbidden());
        member("member-no-account", "club-a", null, "ACTIVE");
        create(admin, "member-no-account", HOST).andExpect(status().isForbidden());
        assertThat(mongo.findAll(SecurityEvent.class).stream().filter(e -> e.type().name().equals("IMPERSONATION_DENIED"))).hasSize(3);
    }

    @Test void T_01_12_handoffHonorsRevokedAccountVersionAndVerifiedDestination() throws Exception {
        String admin = bearer(login());
        String code = handoff(admin, "clubs-admin").path("code").asText();
        try (var scope = TenantContext.open("club-a")) { tokens.revokeAll("account-a"); }
        exchange(code, "clubs-admin", HOST).andExpect(status().isBadRequest());
        clock.advance(Duration.ofSeconds(1));
        admin = bearer(login());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("club-a")), new Update().set("domains.2.status", "PENDING"), Club.class);
        requestHandoff(admin, "clubs-admin", HOST).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_HOST"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("club-a")), new Update().set("domains.2.status", "VERIFIED"), Club.class);
        code = handoff(admin, "clubs-admin").path("code").asText();
        clock.advance(Duration.ofSeconds(59));
        exchange(code, "clubs-admin", HOST).andExpect(status().isOk());
        mvc.perform(post("/oauth2/token").header("Host", HOST).contentType("application/x-www-form-urlencoded")
                .param("grant_type", HandoffService.GRANT).param("token", "missing-client"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @TestConfiguration static class ProbeConfiguration {
        @Bean ProbeController probeController(MongoTemplate mongo) { return new ProbeController(mongo); }
    }
    /** A test-only audited write exercises S08's future CurrentUser integration without implementing bookings. */
    @RestController public static class ProbeController {
        private final MongoTemplate mongo;
        public ProbeController(MongoTemplate mongo) { this.mongo = mongo; }
        @PostMapping("/api/v1/test/impersonation-probe") @PreAuthorize("hasRole('MEMBER')") @Transactional
        @Audited(action = AuditAction.PARAMETER_CHANGED, entityType = "'Parameter'", entity = "#result['id']")
        public Map<String, Object> write() {
            var user = CurrentUser.current();
            var values = Map.<String, Object>of("id", "probe", "origin", user.origin().name(), "accountId", user.accountId());
            mongo.getCollection("impersonation_probes").insertOne(new Document(values).append("clubId", TenantContext.require()));
            return values;
        }
        @GetMapping("/api/v1/test/instructor-probe") @PreAuthorize("hasRole('INSTRUCTOR')") public void instructor() { }
    }
}
