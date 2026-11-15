package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.HandoffService;
import com.agilityhub.core.identity.application.TokenService;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.shared.application.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jwt.SignedJWT;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FE-03 (S01 R-01-13, T-01-12; owner decision of 13 Nov 2026): a handoff link opened on a host that already holds another account's
 * session is refused. At `POST /oauth2/token` with the handoff grant, an account's code redeemed with the host's `ah_refresh` cookie of
 * that client holding a live session of another account answers `409 HANDOFF_SESSION_CONFLICT` with the shared error body and no
 * details, leaves the code unused, issues no tokens and sends no Set-Cookie; the host's session keeps working. The same account's
 * cookie, no cookie or a dead one redeem as before, and an «Entra com l'abonat» launch code is never refused so (E47). Fictional data.
 */
class HandoffSessionConflictIT extends IdentityIntegrationSupport {
    static final String ADMIN_HOST = "admin.example.test";
    static final String PRODUCT = "clubsadmin.agilitydoghub.com";
    static final String OTHER_EMAIL = "second@example.test";
    static final String PLATFORM_EMAIL = "platform@example.test";

    @BeforeEach void secondAccount() {
        for (String collection : List.of("members", "impersonation_grants", "handoff_codes", "audit_entries")) { mongo.remove(new Query(), collection); }
        mongo.updateFirst(Query.query(Criteria.where("_id").is("club-a")), new Update().push("domains",
                new Document("host", ADMIN_HOST).append("app", "clubs-admin").append("status", "VERIFIED").append("primary", true)), Club.class);
        hosts.invalidate(); configs.invalidate("club-a");
        accounts.save(new Account("account-b", OTHER_EMAIL, "Example Second", "ca", passwords.hash(PASSWORD), Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        try (var scope = TenantContext.open("club-a")) {
            memberships.insert(new Membership("membership-b", "account-b", "club-a", "member-b", Set.of(Role.MEMBER, Role.ADMIN), Membership.Status.ACTIVE, Role.MEMBER));
        }
        mongo.getCollection("members").insertOne(new Document("_id", "member-b").append("clubId", "club-a").append("accountId", "account-b").append("status", "ACTIVE"));
    }
    JsonNode session(String host, String client, String email) throws Exception {
        return readTokens(mvc.perform(post("/oauth2/token").header("Host", host).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "password").param("client_id", client).param("username", email).param("password", PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse());
    }
    static String bearer(JsonNode tokens) { return "Bearer " + tokens.path("access_token").asText(); }
    static String subject(JsonNode tokens) throws Exception { return SignedJWT.parse(tokens.path("access_token").asText()).getJWTClaimsSet().getSubject(); }
    String code(JsonNode source, String target) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/auth/handoff").header("Host", HOST).header("Authorization", bearer(source))
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("targetClientId", target))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("code").asText();
    }
    /** The exchange as the destination app sends it: same-origin, with whatever `ah_refresh` cookie the host holds. */
    ResultActions exchange(String code, String client, String host, String cookie) throws Exception {
        var request = post("/oauth2/token").header("Host", host).header("Origin", "https://" + host).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", HandoffService.GRANT).param("client_id", client).param("token", code);
        if (cookie != null) { request.cookie(new jakarta.servlet.http.Cookie(RefreshCookies.NAME, cookie)); }
        return mvc.perform(request);
    }
    HandoffCode stored(String code) {
        return mongo.findOne(Query.query(Criteria.where("codeHash").is(TokenService.digest(code))), HandoffCode.class);
    }
    /** The refusal: 409 with the shared body, no details, no tokens and no cookie; nothing is written and the code stays unused. */
    void refused(String code, String client, String host, String cookie) throws Exception {
        long sessions = mongo.count(new Query(), RefreshToken.class);
        MockHttpServletResponse response = exchange(code, client, host, cookie).andExpect(status().isConflict())
                .andExpect(header().doesNotExist("Set-Cookie")).andReturn().getResponse();
        var body = mapper.readTree(response.getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("HANDOFF_SESSION_CONFLICT");
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.path("traceId").asText()).isNotBlank();
        assertThat(body.path("details").size()).isZero();
        assertThat(body.has("access_token")).isFalse();
        assertThat(response.getContentAsString()).doesNotContain("account-a", "account-b", "account-platform", "admin@example.test", OTHER_EMAIL,
                PLATFORM_EMAIL, "Example");
        assertThat(mongo.count(new Query(), RefreshToken.class)).isEqualTo(sessions);
        assertThat(stored(code).usedAt()).isNull();
    }

    @Test void FE_03_T_01_12_anotherAccountsLiveCookieKeepsTheHostsSessionAndLeavesTheCodeRedeemableWithoutIt() throws Exception {
        // Back office → club app (the profile switch): B's code opened where A is signed in to the club app.
        String memberCookie = refreshValue(session(HOST, "clubs-app", "admin@example.test"));
        String toApp = code(session(ADMIN_HOST, "clubs-admin", OTHER_EMAIL), "clubs-app");
        refused(toApp, "clubs-app", HOST, memberCookie);
        var kept = readTokens(refresh(memberCookie, HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        assertThat(subject(kept)).as("the host's session is kept").isEqualTo("account-a");
        var redeemed = readTokens(exchange(toApp, "clubs-app", HOST, null).andExpect(status().isOk())
                .andExpect(cookie().exists(RefreshCookies.NAME)).andReturn().getResponse());
        assertThat(subject(redeemed)).as("the code still opens its own account without that cookie").isEqualTo("account-b");

        // Club app → back office (03b's portal): B's code opened where A is signed in to the back office.
        String adminCookie = refreshValue(session(ADMIN_HOST, "clubs-admin", "admin@example.test"));
        String toAdmin = code(session(HOST, "clubs-app", OTHER_EMAIL), "clubs-admin");
        refused(toAdmin, "clubs-admin", ADMIN_HOST, adminCookie);
        assertThat(subject(readTokens(refresh(adminCookie, ADMIN_HOST, "clubs-admin").andExpect(status().isOk()).andReturn().getResponse())))
                .isEqualTo("account-a");
        assertThat(subject(readTokens(exchange(toAdmin, "clubs-admin", ADMIN_HOST, null).andExpect(status().isOk()).andReturn().getResponse())))
                .isEqualTo("account-b");
    }

    @Test void FE_03_theSameAccountsCookieAndNoCookieRedeemAsBefore() throws Exception {
        var backOffice = session(ADMIN_HOST, "clubs-admin", OTHER_EMAIL);
        String own = refreshValue(session(HOST, "clubs-app", OTHER_EMAIL));
        var replaced = exchange(code(backOffice, "clubs-app"), "clubs-app", HOST, own).andExpect(status().isOk())
                .andExpect(cookie().exists(RefreshCookies.NAME)).andReturn().getResponse();
        assertThat(subject(readTokens(replaced))).isEqualTo("account-b");
        String fresh = code(backOffice, "clubs-app");
        exchange(fresh, "clubs-app", HOST, null).andExpect(status().isOk()).andExpect(cookie().exists(RefreshCookies.NAME));
        assertThat(stored(fresh).usedAt()).isNotNull();
    }

    @Test void FE_03_aDeadUnknownOrOtherClientsCookieRedeemsAsBefore() throws Exception {
        var backOffice = session(ADMIN_HOST, "clubs-admin", OTHER_EMAIL);
        // Signed out (R-01-10): the family is revoked.
        var signedOut = session(HOST, "clubs-app", "admin@example.test");
        mvc.perform(post("/oauth2/revoke").header("Host", HOST).header("Authorization", bearer(signedOut)).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        exchange(code(backOffice, "clubs-app"), "clubs-app", HOST, refreshValue(signedOut)).andExpect(status().isOk());
        // Expired: past its expiresAt.
        String expired = refreshValue(session(HOST, "clubs-app", "admin@example.test"));
        mongo.updateMulti(Query.query(Criteria.where("tokenHash").is(TokenService.digest(expired))), new Update().set("expiresAt", clock.instant().minusSeconds(1)), RefreshToken.class);
        exchange(code(backOffice, "clubs-app"), "clubs-app", HOST, expired).andExpect(status().isOk());
        // Revoked for every device by a newer family version (R-01-10), nothing else changed.
        String older = refreshValue(session(HOST, "clubs-app", "admin@example.test"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("account-a")), new Update().inc("security.tokenFamilyVersion", 1), Account.class);
        exchange(code(backOffice, "clubs-app"), "clubs-app", HOST, older).andExpect(status().isOk());
        // Another client's session on that host, and a value no session has.
        String otherClient = refreshValue(session(HOST, "clubs-admin", "admin@example.test"));
        exchange(code(backOffice, "clubs-app"), "clubs-app", HOST, otherClient).andExpect(status().isOk());
        exchange(code(backOffice, "clubs-app"), "clubs-app", HOST, "fictional-unknown-refresh-value").andExpect(status().isOk());
    }

    @Test void FE_03_E47_anImpersonationLaunchCodeIsNeverRefusedForTheHostsSession() throws Exception {
        // B is signed in to the club app on this browser; A, an ADMIN, opens «Entra com l'abonat» for B's member record here.
        String memberCookie = refreshValue(session(HOST, "clubs-app", OTHER_EMAIL));
        var issued = mapper.readTree(mvc.perform(post("/api/v1/members/member-b/impersonation-token").header("Host", HOST)
                .header("Authorization", bearer(session(HOST, "clubs-app", "admin@example.test"))).contentType("application/json")
                .content("{\"reason\":\"Help with a booking\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String launch = java.net.URI.create(issued.path("launchUrl").asText()).getQuery().substring("handoff=".length());
        assertThat(stored(launch).accountId()).as("the launch code is the actor's").isEqualTo("account-a");
        var opened = mapper.readTree(exchange(launch, "clubs-app", HOST, memberCookie).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie")).andReturn().getResponse().getContentAsString());
        assertThat(SignedJWT.parse(opened.path("access_token").asText()).getJWTClaimsSet().getBooleanClaim("imp")).isTrue();
        assertThat(subject(readTokens(refresh(memberCookie, HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse())))
                .isEqualTo("account-b");
    }

    @Test void FE_03_R_01_02_theConsolesProductHostRefusesACodeWhereAnotherPlatformSessionIsOpen() throws Exception {
        accounts.save(new Account("account-platform", PLATFORM_EMAIL, "Example Platform", "ca", passwords.hash(PASSWORD),
                Set.of(Account.PlatformRole.AGILITYHUB_ADMIN), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("account-a")), new Update().set("platformRoles", List.of("AGILITYHUB_ADMIN")), Account.class);
        String platformCookie = refreshValue(session(PRODUCT, "clubs-admin", PLATFORM_EMAIL));
        String console = code(session(HOST, "clubs-app", "admin@example.test"), "clubs-admin");
        refused(console, "clubs-admin", PRODUCT, platformCookie);
        assertThat(subject(readTokens(refresh(platformCookie, PRODUCT, "clubs-admin").andExpect(status().isOk()).andReturn().getResponse())))
                .isEqualTo("account-platform");
        var opened = readTokens(exchange(console, "clubs-admin", PRODUCT, null).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(subject(opened)).isEqualTo("account-a");
        assertThat(SignedJWT.parse(opened.path("access_token").asText()).getJWTClaimsSet().getClaims()).doesNotContainKey("clubId");
    }
}
