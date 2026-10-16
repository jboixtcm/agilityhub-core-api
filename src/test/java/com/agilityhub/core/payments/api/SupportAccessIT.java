package com.agilityhub.core.payments.api;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * S17 R-17-10 / T-17-09 (E10-T04): «Entra com a administrador del club». The support token opens the club's administration
 * as an audited ADMIN (`origin = PLATFORM_SUPPORT`), never a personal-data export, a SEPA file or a full IBAN, and lives only
 * as long as its grant: 60 minutes, `/oauth2/revoke`, or its account's platform role.
 */
class SupportAccessIT extends BillingItSupport {
    static final String ACTOR = "bill-platform";
    @Autowired AccountRepository accounts;

    @BeforeEach void platformAccount() {
        mongo.remove(new Query(), "impersonation_grants");
        accounts.save(new Account(ACTOR, "support@example.test", "Pat Support", "ca", null, Set.of(Account.PlatformRole.AGILITYHUB_ADMIN),
                Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
    }
    MockHttpServletRequestBuilder console(MockHttpServletRequestBuilder call, String subject) {
        return call.header("Host", "unknown.example.test").with(jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN")));
    }
    MockHttpServletResponse start(String club, String key, String subject) throws Exception {
        return mvc.perform(console(post("/api/v1/platform/clubs/" + club + "/support-access"), subject).header("Idempotency-Key", key)
                .contentType("application/json").content("{\"reason\":\"Help with the August receipts\"}")).andReturn().getResponse();
    }
    String token() throws Exception {
        var response = start(CLUB, UUID.randomUUID().toString(), ACTOR);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return mapper.readTree(response.getContentAsString()).path("token").asText();
    }
    MockHttpServletResponse as(String token, MockHttpServletRequestBuilder call) throws Exception {
        return mvc.perform(call.header("Host", HOST).header("Authorization", "Bearer " + token)).andReturn().getResponse();
    }
    static String code(MockHttpServletResponse response) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString()).path("code").asText();
    }

    @Test @AuditCovers(AuditAction.SUPPORT_ACCESS_STARTED)
    void T_17_09_R_17_10_supportTokenOpensTheClubAsAnAuditedAdminWithoutPersonalExports() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = start(CLUB, key, ACTOR);
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(201);
        JsonNode issued = mapper.readTree(first.getContentAsString());
        String token = issued.path("token").asText();
        assertThat(issued.path("expiresAt").asText()).isEqualTo(clock.instant().plus(Duration.ofMinutes(60)).toString());
        // The same key answers the same token (a lost answer would leave a grant nobody can use), never stored in clear.
        assertThat(start(CLUB, key, ACTOR).getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(mongo.count(new Query(), "impersonation_grants")).isEqualTo(1);
        var records = mongo.find(Query.query(Criteria.where("key").is(key)), Document.class, "idempotency_records");
        assertThat(records).singleElement().satisfies(record -> assertThat(new String(record.get("responseBody", org.bson.types.Binary.class).getData(),
                java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(token, "token", "expiresAt"));
        var claims = SignedJWT.parse(token).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(ACTOR);
        assertThat(claims.getStringClaim("actorAccountId")).isEqualTo(ACTOR);
        assertThat(claims.getStringClaim("clubId")).isEqualTo(CLUB);
        assertThat(claims.getStringListClaim("roles")).containsExactly("ADMIN");
        assertThat(claims.getBooleanClaim("support")).isTrue();
        assertThat(claims.getClaim("platformRoles")).isNull();
        assertThat(claims.getAudience()).containsExactly("clubs-admin");
        var grant = mongo.findOne(new Query(), Document.class, "impersonation_grants");
        assertThat(grant.getString("type")).isEqualTo("SUPPORT");
        assertThat(grant.getString("clubId")).isEqualTo(CLUB);
        assertThat(grant.getString("_id")).isEqualTo(claims.getJWTID());
        // GET /me: an ADMIN membership without a member, and the banner's support block.
        var me = as(token, get("/api/v1/me"));
        assertThat(me.getStatus()).as(me.getContentAsString()).isEqualTo(200);
        var body = mapper.readTree(me.getContentAsString());
        assertThat(body.path("membership").path("roles")).containsExactly(mapper.valueToTree("ADMIN"));
        assertThat(body.path("membership").has("memberId")).isFalse();
        assertThat(body.path("support")).isEqualTo(mapper.valueToTree(Map.of("actorName", "Pat Support", "expiresAt", issued.path("expiresAt").asText())));
        assertThat(body.path("account").path("platformRoles")).isEmpty();
        // The member's IBAN stays masked; the routes that hand over personal data or full IBANs are closed.
        var member = as(token, get("/api/v1/members/puig"));
        assertThat(member.getStatus()).as(member.getContentAsString()).isEqualTo(200);
        var stored = mongo.findById("puig", Document.class, "members").get("paymentMethod", Document.class);
        String last4 = stored.getString("ibanLast4") != null ? stored.getString("ibanLast4") : stored.getString("iban").substring(stored.getString("iban").length() - 4);
        assertThat(mapper.readTree(member.getContentAsString()).path("paymentMethod").path("maskedAccount").asText()).endsWith(" " + last4).startsWith("····");
        if (stored.getString("iban") != null) { assertThat(member.getContentAsString()).doesNotContain(stored.getString("iban")); }
        assertThat(member.getContentAsString()).doesNotContain(IBAN, "ibanEncrypted", "\"iban\"");
        for (var restricted : List.of(get("/api/v1/members/export").param("format", "xlsx"), post("/api/v1/members/export").param("format", "xlsx"),
                get("/api/v1/dogs/export").param("format", "xlsx"), get("/api/v1/audit-entries/export").param("format", "xlsx"),
                post("/api/v1/members/puig/data-export"), get("/api/v1/exports"), get("/api/v1/exports/some-export/download"),
                get("/api/v1/remittances/some-remittance/file"), get("/api/v1/billing/exports"), get("/api/v1/invoices/export"),
                post("/api/v1/members/puig/impersonation-token").contentType("application/json").content("{}"),
                put("/api/v1/me/password").contentType("application/json").content("{}"))) {
            var response = as(token, restricted);
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(403);
            assertThat(code(response)).isEqualTo("SUPPORT_RESTRICTED");
        }
        // The console itself is not reachable with it: it carries no platform role.
        assertThat(as(token, get("/api/v1/platform/clubs/" + CLUB)).getStatus()).isEqualTo(403);
        // An ADMIN write is audited as the AgilityHub team's.
        var written = as(token, put("/api/v1/parameters/bookings.lateCancelThresholdMinutes").contentType("application/json")
                .content("{\"version\":0,\"value\":120,\"reason\":\"Support fix\"}"));
        assertThat(written.getStatus()).as(written.getContentAsString()).isEqualTo(200);
        var change = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("PARAMETER_CHANGED")), Document.class, "audit_entries");
        assertThat(change.getString("origin")).isEqualTo("PLATFORM_SUPPORT");
        assertThat(change.getString("actorAccountId")).isEqualTo(ACTOR);
        assertThat(change.getString("actorName")).isEqualTo("Pat Support");
        assertThat(change.getBoolean("support")).isTrue();
        // The club sees the support access in its own audit, with its reason.
        var audit = mvc.perform(get("/api/v1/audit-entries").param("filter", "action:eq:SUPPORT_ACCESS_STARTED").header("Host", HOST)
                .with(jwt().jwt(j -> j.subject("bill-admin").claim("clubId", CLUB)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andReturn().getResponse();
        assertThat(audit.getStatus()).as(audit.getContentAsString()).isEqualTo(200);
        var items = mapper.readTree(audit.getContentAsString()).path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("reason").asText()).isEqualTo("Help with the August receipts");
        assertThat(items.get(0).path("entityId").asText()).isEqualTo(claims.getJWTID());
        assertThat(audit.getContentAsString()).doesNotContain(token);
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("SupportAccessStarted")), "domain_events")).isEqualTo(1);
        // The other club never sees it.
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(OTHER).and("action").is("SUPPORT_ACCESS_STARTED")), "audit_entries")).isZero();
    }

    @Test void T_17_09_R_17_10_supportAccessEndsWithItsGrantItsRoleOrItsClub() throws Exception {
        // 60 minutes (platform.supportAccessMinutes), then 401.
        String token = token();
        assertThat(as(token, get("/api/v1/me")).getStatus()).isEqualTo(200);
        clock.setInstant(NOW.plus(Duration.ofMinutes(60)));
        assertThat(as(token, get("/api/v1/me")).getStatus()).isEqualTo(401);
        clock.setInstant(NOW);
        // «Surt»: the actor ends it with /oauth2/revoke.
        token = token();
        var revoked = as(token, post("/oauth2/revoke").contentType("application/json").content(mapper.writeValueAsString(Map.of("token", token))));
        assertThat(revoked.getStatus()).as(revoked.getContentAsString()).isEqualTo(200);
        assertThat(as(token, get("/api/v1/members/puig")).getStatus()).isEqualTo(401);
        // An account that loses its platform role loses every live support access at once.
        token = token();
        accounts.platformRoles(ACTOR, Set.of());
        assertThat(as(token, get("/api/v1/members/puig")).getStatus()).isEqualTo(401);
        accounts.platformRoles(ACTOR, Set.of(Account.PlatformRole.AGILITYHUB_ADMIN));
        // Another club's host is another tenant.
        token = token();
        var mismatch = mvc.perform(get("/api/v1/members/puig").header("Host", OTHER_HOST).header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(mismatch.getStatus()).isEqualTo(403);
        assertThat(code(mismatch)).isEqualTo("TENANT_MISMATCH");
        // A shorter club setting shortens the grant.
        var minutes = mvc.perform(console(put("/api/v1/platform/clubs/" + CLUB + "/parameters/platform.supportAccessMinutes"), ACTOR)
                .contentType("application/json").content("{\"version\":0,\"value\":30}")).andReturn().getResponse();
        assertThat(minutes.getStatus()).as(minutes.getContentAsString()).isEqualTo(200);
        var shorter = mapper.readTree(start(CLUB, UUID.randomUUID().toString(), ACTOR).getContentAsString());
        assertThat(shorter.path("expiresAt").asText()).isEqualTo(NOW.plus(Duration.ofMinutes(30)).toString());
    }

    @Test void T_17_10_R_17_12_onlyAnActivePlatformAccountStartsASupportAccess() throws Exception {
        // A token that claims the platform role, for an account that does not hold it (or no longer does).
        accounts.save(new Account("bill-not-platform", "plain@example.test", "Plain Account", "ca", null, Set.of(),
                Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        for (String subject : List.of("bill-not-platform", "bill-unknown-account")) {
            var refused = start(CLUB, UUID.randomUUID().toString(), subject);
            assertThat(refused.getStatus()).as(subject + " " + refused.getContentAsString()).isEqualTo(403);
            assertThat(code(refused)).isEqualTo("FORBIDDEN");
        }
        var missing = start("bill-missing", UUID.randomUUID().toString(), ACTOR);
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("CLUB_NOT_FOUND");
        var club = mvc.perform(post("/api/v1/platform/clubs/" + CLUB + "/support-access").header("Host", HOST).contentType("application/json")
                .content("{\"reason\":\"x\"}").with(jwt().jwt(j -> j.subject("bill-admin").claim("clubId", CLUB)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andReturn().getResponse();
        assertThat(club.getStatus()).isEqualTo(403);
        assertThat(mongo.count(new Query(), "impersonation_grants")).isZero();
        assertThat(mongo.count(Query.query(Criteria.where("action").is("SUPPORT_ACCESS_STARTED")), "audit_entries")).isZero();
    }
}
