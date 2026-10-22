package com.agilityhub.core.identity.api;

import com.agilityhub.core.clubs.messaging.application.FakeEmailSender;
import com.agilityhub.core.identity.application.HandoffService;
import com.agilityhub.core.identity.application.MagicLinkService;
import com.agilityhub.core.identity.persistence.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * S01 R-01-02 and S17 §2 «Entrada» (ruling E157, E11-T19 step 1): the console signs in on its product host, which resolves no club.
 * `clubs-admin` gets a session without club context there, only for an account holding `AGILITYHUB_ADMIN` (`403 PLATFORM_ONLY` for
 * the rest), with the refresh cookie on that host; `id-web` stays on the ID host and a club host keeps its behaviour.
 */
class ConsoleProductHostIT extends IdentityIntegrationSupport {
    static final String PRODUCT = "clubsadmin.agilitydoghub.com";
    static final String ID_HOST = "id.agilitydoghub.com";
    static final String PLATFORM_EMAIL = "platform@example.test";

    @BeforeEach void platformAdmin() {
        mongo.remove(new Query(), HandoffCode.class);
        // The club's own console host (R-01-13's handoff target URL); the product host is not one of the club's domains.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("club-a")), new Update().push("domains", new org.bson.Document("host", "admin.example.test")
                .append("app", "clubs-admin").append("status", "VERIFIED").append("primary", true)), com.agilityhub.core.platform.persistence.Club.class);
        hosts.invalidate(); configs.invalidate("club-a");
        accounts.save(new Account("account-platform", PLATFORM_EMAIL, "Example Platform", "ca", passwords.hash(PASSWORD),
                Set.of(Account.PlatformRole.AGILITYHUB_ADMIN), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
    }
    ResultActions consoleLogin(String host, String client, String email) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Host", host).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "password").param("client_id", client).param("username", email).param("password", PASSWORD));
    }
    JsonNode platformSession() throws Exception {
        return readTokens(consoleLogin(PRODUCT, "clubs-admin", PLATFORM_EMAIL).andExpect(status().isOk()).andReturn().getResponse());
    }
    static String bearer(JsonNode tokens) { return "Bearer " + tokens.path("access_token").asText(); }
    static com.nimbusds.jwt.JWTClaimsSet claims(JsonNode tokens) throws Exception {
        return SignedJWT.parse(tokens.path("access_token").asText()).getJWTClaimsSet();
    }
    void requestMagic(String email, String host, String client) throws Exception {
        mvc.perform(post("/api/v1/auth/magic-link").header("Host", host).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("email", email, "purpose", "LOGIN", "client_id", client))))
                .andExpect(status().isAccepted());
        awaitMail();
    }
    ResultActions grant(String grant, String value, String host) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Host", host).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", grant).param("client_id", "clubs-admin").param("token", value));
    }

    @Test void T_01_07_R_01_02_productHostIssuesConsoleSessionsWithoutClubOnlyToPlatformAdmins() throws Exception {
        for (String host : new String[]{PRODUCT, "ClubsAdmin.AgilityDogHub.com:443"}) {
            var response = consoleLogin(host, "clubs-admin", PLATFORM_EMAIL).andExpect(status().isOk())
                    .andExpect(cookie().exists(RefreshCookies.NAME)).andExpect(cookie().path(RefreshCookies.NAME, "/oauth2/token"))
                    .andExpect(cookie().httpOnly(RefreshCookies.NAME, true)).andExpect(jsonPath("$.refresh_token").doesNotExist())
                    .andReturn().getResponse();
            var claims = claims(readTokens(response));
            assertThat(claims.getAudience()).containsExactly("clubs-admin");
            assertThat(claims.getStringListClaim("platformRoles")).containsExactly("AGILITYHUB_ADMIN");
            assertThat(claims.getClaims()).doesNotContainKeys("clubId", "roles", "activeProfile", "memberId");
        }
        assertThat(mongo.findAll(RefreshToken.class)).allSatisfy(token -> {
            assertThat(token.clubId()).isNull(); assertThat(token.clientId()).isEqualTo("clubs-admin");
        });
        // A club administrator without the platform role: 403 PLATFORM_ONLY, no cookie, no session.
        long sessions = mongo.count(new Query(), RefreshToken.class);
        consoleLogin(PRODUCT, "clubs-admin", "admin@example.test").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_ONLY")).andExpect(cookie().doesNotExist(RefreshCookies.NAME));
        assertThat(mongo.count(new Query(), RefreshToken.class)).isEqualTo(sessions);
        // Other clients keep R-01-02's «unknown host» there: id-web stays on the ID host, clubs-app needs a club host.
        for (String client : new String[]{"id-web", "clubs-app"}) {
            consoleLogin(PRODUCT, client, PLATFORM_EMAIL).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_HOST"));
        }
        // The ID host is not the console's, and an unknown host is still unknown.
        consoleLogin(ID_HOST, "clubs-admin", PLATFORM_EMAIL).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_HOST"));
        consoleLogin("unknown.example.test", "clubs-admin", PLATFORM_EMAIL).andExpect(status().isNotFound());
        // A club host is unchanged: club context from its membership; the platform admin without one → NO_MEMBERSHIP.
        assertThat(claims(readTokens(consoleLogin(HOST, "clubs-admin", "admin@example.test").andExpect(status().isOk()).andReturn().getResponse()))
                .getStringClaim("clubId")).isEqualTo("club-a");
        consoleLogin(HOST, "clubs-admin", PLATFORM_EMAIL).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_MEMBERSHIP"));
    }

    @Test void T_01_07_R_01_17_consoleSessionReadsMeAndThePlatformButNoClubRoute() throws Exception {
        var console = platformSession();
        var me = mapper.readTree(mvc.perform(get("/api/v1/me").header("Host", PRODUCT).header("Authorization", bearer(console)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(me.path("membership").isMissingNode() || me.path("membership").isNull()).isTrue();
        assertThat(me.path("account").path("platformRoles")).containsExactly(mapper.valueToTree("AGILITYHUB_ADMIN"));
        mvc.perform(get("/api/v1/platform/clubs").header("Host", PRODUCT).header("Authorization", bearer(console))).andExpect(status().isOk());
        // No club context by design: a club route answers 403 on the product host and on a club host alike.
        for (String host : new String[]{PRODUCT, HOST}) {
            mvc.perform(get("/api/v1/members").header("Host", host).header("Authorization", bearer(console)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_MEMBERSHIP"));
        }
    }

    @Test void T_01_04_R_01_06_productHostRefreshRotatesKeepsTheGraceAndRevokesAReusedFamily() throws Exception {
        var first = platformSession();
        // R-01-13: the refresh is bound to its client and host: a club host or another client does not find it.
        refresh(refreshValue(first), HOST, "clubs-admin").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_EXPIRED"));
        refresh(refreshValue(first), PRODUCT, "clubs-app").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_HOST"));
        clock.advance(Duration.ofMinutes(1));
        var second = readTokens(refresh(refreshValue(first), PRODUCT, "clubs-admin").andExpect(status().isOk())
                .andExpect(cookie().exists(RefreshCookies.NAME)).andReturn().getResponse());
        assertThat(refreshValue(second)).isNotEqualTo(refreshValue(first));
        assertThat(claims(second).getClaims()).doesNotContainKey("clubId");
        assertThat(claims(second).getStringListClaim("platformRoles")).containsExactly("AGILITYHUB_ADMIN");
        // Inside auth.refreshReuseGraceSeconds the rotated token is accepted once more (a sibling); after it, a reuse.
        refresh(refreshValue(first), PRODUCT, "clubs-admin").andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(1));
        refresh(refreshValue(first), PRODUCT, "clubs-admin").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_REUSED"))
                .andExpect(cookie().maxAge(RefreshCookies.NAME, 0));
        refresh(refreshValue(second), PRODUCT, "clubs-admin").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_EXPIRED"));
        // A platform role removed later stops the next refresh.
        var third = platformSession();
        mongo.updateFirst(Query.query(Criteria.where("_id").is("account-platform")), new Update().set("platformRoles", java.util.List.of()), Account.class);
        refresh(refreshValue(third), PRODUCT, "clubs-admin").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLATFORM_ONLY"));
    }

    @Test void T_01_08_R_01_04_productHostMagicLinkReachesOnlyPlatformAdminsAndOpensThere() throws Exception {
        var mailbox = (FakeEmailSender) emailSender;
        // Neutral 202 and no mail for an account without the platform role, or for the ID's client on the product host.
        requestMagic("admin@example.test", PRODUCT, "clubs-admin");
        requestMagic(PLATFORM_EMAIL, PRODUCT, "id-web");
        assertThat(mailbox.messages()).isEmpty();
        requestMagic(PLATFORM_EMAIL, PRODUCT, "clubs-admin");
        var matcher = Pattern.compile("https://" + Pattern.quote(PRODUCT) + "/activacio[?]t=([A-Za-z0-9_-]{43})").matcher(mailbox.lastTo(PLATFORM_EMAIL).text());
        assertThat(matcher.find()).isTrue();
        String value = matcher.group(1);
        var stored = mongo.findAll(MagicLinkToken.class).getFirst();
        assertThat(stored.clubId()).isNull();
        assertThat(stored.clientId()).isEqualTo("clubs-admin");
        // It opens only on the console's host: a club host and any other host refuse it, without using it up.
        grant(MagicLinkService.GRANT, value, HOST).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MAGIC_LINK_INVALID"));
        grant(MagicLinkService.GRANT, value, "unknown.example.test").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MAGIC_LINK_INVALID"));
        var console = readTokens(grant(MagicLinkService.GRANT, value, PRODUCT).andExpect(status().isOk())
                .andExpect(cookie().exists(RefreshCookies.NAME)).andReturn().getResponse());
        assertThat(claims(console).getClaims()).doesNotContainKey("clubId");
        assertThat(claims(console).getStringListClaim("platformRoles")).containsExactly("AGILITYHUB_ADMIN");
    }

    @Test void T_01_12_R_01_13_productHostHandoffOpensAPlatformSessionOnlyForPlatformAdmins() throws Exception {
        // A club administrator who is also a platform admin hands the session over to the console.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("account-a")), new Update().set("platformRoles", java.util.List.of("AGILITYHUB_ADMIN")), Account.class);
        String code = handoff();
        var console = readTokens(grant(HandoffService.GRANT, code, PRODUCT).andExpect(status().isOk())
                .andExpect(cookie().exists(RefreshCookies.NAME)).andReturn().getResponse());
        assertThat(claims(console).getAudience()).containsExactly("clubs-admin");
        assertThat(claims(console).getClaims()).doesNotContainKeys("clubId", "roles");
        grant(HandoffService.GRANT, code, PRODUCT).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HANDOFF_INVALID"));
        // Without the platform role: 403 PLATFORM_ONLY and the code stays unused.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("account-a")), new Update().set("platformRoles", java.util.List.of()), Account.class);
        String other = handoff();
        grant(HandoffService.GRANT, other, PRODUCT).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLATFORM_ONLY"));
        assertThat(mongo.find(Query.query(Criteria.where("codeHash").is(com.agilityhub.core.identity.application.TokenService.digest(other))), HandoffCode.class))
                .singleElement().satisfies(stored -> assertThat(stored.usedAt()).isNull());
    }
    String handoff() throws Exception {
        var session = login();
        var created = mapper.readTree(mvc.perform(post("/api/v1/auth/handoff").header("Host", HOST).header("Authorization", bearer(session))
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("targetClientId", "clubs-admin"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return created.path("code").asText();
    }
}
