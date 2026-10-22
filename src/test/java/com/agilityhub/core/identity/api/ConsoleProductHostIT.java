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
    String handoff() throws Exception { return handoff("clubs-admin"); }
    String handoff(String target) throws Exception {
        var session = login();
        var created = mapper.readTree(mvc.perform(post("/api/v1/auth/handoff").header("Host", HOST).header("Authorization", bearer(session))
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("targetClientId", target))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return created.path("code").asText();
    }

    // Round 2 (E161): the product host issues and renews only console sessions without a club, for every grant.
    static final String VERIFIER = "a".repeat(64);
    static final String CONSOLE_CALLBACK = "https://admin.example.test/oidc/callback";
    @org.springframework.test.context.DynamicPropertySource static void consoleCallback(org.springframework.test.context.DynamicPropertyRegistry registry) {
        // The console's OIDC callback on the club's own console domain, so an authorization code carries the club (OidcService).
        registry.add("OIDC_CLUBS_ADMIN_REDIRECT_URI", () -> CONSOLE_CALLBACK);
    }
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository registered;
    ResultActions grantAs(String client, String grant, String value, String host) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Host", host).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", grant).param("client_id", client).param("token", value));
    }
    /** A real authorization code of `client` (S01 R-01-13's OIDC flow on the ID host, as `OidcIT` runs it). */
    String authorizationCode(String client) throws Exception {
        String redirect = registered.findByClientId(client).getRedirectUris().iterator().next();
        var started = mvc.perform(get("/oauth2/authorize").header("Host", ID_HOST).secure(true).param("response_type", "code")
                .param("client_id", client).param("redirect_uri", redirect).param("scope", "openid offline_access").param("state", "s")
                .param("code_challenge", com.agilityhub.core.identity.application.TokenService.digest(VERIFIER)).param("code_challenge_method", "S256"))
                .andExpect(status().isFound()).andReturn().getResponse();
        String flow = org.springframework.web.util.UriComponentsBuilder.fromUriString(started.getRedirectedUrl()).build().getQueryParams().getFirst("flow");
        String access = mapper.readTree(consoleLogin(ID_HOST, "id-web", "admin@example.test").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("access_token").asText();
        var completed = mvc.perform(post("/oauth2/session").header("Host", ID_HOST).header("Origin", "https://" + ID_HOST)
                .header("Authorization", "Bearer " + access).cookie(started.getCookie(com.agilityhub.core.identity.application.OidcService.COOKIE))
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("flow", flow))))
                .andExpect(status().isOk()).andReturn().getResponse();
        String url = mapper.readTree(completed.getContentAsString()).path("redirectUrl").asText();
        return org.springframework.web.util.UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("code");
    }
    ResultActions exchange(String client, String code, String host) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Host", host).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code").param("client_id", client).param("code", code)
                .param("redirect_uri", registered.findByClientId(client).getRedirectUris().iterator().next()).param("code_verifier", VERIFIER));
    }
    static org.springframework.test.web.servlet.ResultMatcher invalidGrant() {
        return result -> {
            status().isBadRequest().match(result);
            jsonPath("$.code").value("VALIDATION_ERROR").match(result);
            jsonPath("$.details.oauth2Error").value("invalid_grant").match(result);
        };
    }

    /** A club administrator who holds the platform role or not: on the product host neither gets a club context (the kit's #1). */
    void platformRole(boolean held) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is("account-a")),
                new Update().set("platformRoles", held ? java.util.List.of("AGILITYHUB_ADMIN") : java.util.List.of()), Account.class);
    }

    @Test void T_01_04_R_01_02_productHostRefusesAClubHostsRefreshTokenAsInvalidGrant() throws Exception {
        for (boolean held : new boolean[]{true, false}) {
            platformRole(held);
            // The club host's own console session (R-01-06's cookie, bound to club-a): invalid_grant there, unrotated.
            var clubSession = readTokens(consoleLogin(HOST, "clubs-admin", "admin@example.test").andExpect(status().isOk()).andReturn().getResponse());
            refresh(refreshValue(clubSession), PRODUCT, "clubs-admin").andExpect(invalidGrant()).andExpect(cookie().doesNotExist(RefreshCookies.NAME));
            assertThat(claims(readTokens(refresh(refreshValue(clubSession), HOST, "clubs-admin").andExpect(status().isOk()).andReturn().getResponse()))
                    .getStringClaim("clubId")).isEqualTo("club-a");
        }
        // The platform admin's own console session still renews there, without a club.
        var console = platformSession();
        assertThat(claims(readTokens(refresh(refreshValue(console), PRODUCT, "clubs-admin").andExpect(status().isOk()).andReturn().getResponse()))
                .getClaims()).doesNotContainKey("clubId");
    }

    @Test void T_01_13_R_01_02_productHostNeverRedeemsAClubBoundAuthorizationCode() throws Exception {
        for (boolean held : new boolean[]{true, false}) {
            platformRole(held);
            // A console OIDC code, bound to club-a by its callback: invalid_grant there, and the code left unused.
            String code = authorizationCode("clubs-admin");
            exchange("clubs-admin", code, PRODUCT).andExpect(invalidGrant()).andExpect(cookie().doesNotExist(RefreshCookies.NAME));
            var session = readTokens(exchange("clubs-admin", code, ID_HOST).andExpect(status().isOk()).andReturn().getResponse());
            assertThat(claims(session).getStringClaim("clubId")).isEqualTo("club-a");
        }
    }

    @Test void T_01_13_R_01_02_productHostNeverRenewsAClubBoundOidcRefreshToken() throws Exception {
        for (boolean held : new boolean[]{true, false}) {
            platformRole(held);
            // The console's OIDC refresh token, bound to club-a: the old code restored its club on the product host (200, clubId).
            var session = readTokens(exchange("clubs-admin", authorizationCode("clubs-admin"), ID_HOST).andExpect(status().isOk()).andReturn().getResponse());
            refresh(refreshValue(session), PRODUCT, "clubs-admin").andExpect(invalidGrant()).andExpect(cookie().doesNotExist(RefreshCookies.NAME));
            // Left unrotated: it still renews where it was issued, with its club.
            assertThat(claims(readTokens(refresh(refreshValue(session), ID_HOST, "clubs-admin").andExpect(status().isOk()).andReturn().getResponse()))
                    .getStringClaim("clubId")).isEqualTo("club-a");
        }
    }

    @Test void T_01_08_R_01_02_productHostAnswersAnIdMagicLinkAsAnUnknownHost() throws Exception {
        var mailbox = (FakeEmailSender) emailSender;
        // An ID magic link, issued on the ID host: 404 on the product host, the link left unused; it still opens on the ID host.
        requestMagic("admin@example.test", ID_HOST, "id-web");
        var matcher = Pattern.compile("[?&]t=([A-Za-z0-9_-]{43})").matcher(mailbox.lastTo("admin@example.test").text());
        assertThat(matcher.find()).isTrue();
        String link = matcher.group(1);
        grantAs("id-web", MagicLinkService.GRANT, link, PRODUCT).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_HOST"));
        assertThat(mongo.find(Query.query(Criteria.where("tokenHash").is(com.agilityhub.core.identity.application.TokenService.digest(link))), MagicLinkToken.class))
                .singleElement().satisfies(stored -> assertThat(stored.usedAt()).isNull());
        grantAs("id-web", MagicLinkService.GRANT, link, ID_HOST).andExpect(status().isOk());
    }

    @Test void T_01_12_R_01_02_productHostAnswersAClubAppHandoffAsAnUnknownHost() throws Exception {
        // The club app's handoff code: 404 on the product host (the old code issued a club token there), unused; it opens on the club host.
        String code = handoff("clubs-app");
        grantAs("clubs-app", HandoffService.GRANT, code, PRODUCT).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_HOST"));
        assertThat(mongo.find(Query.query(Criteria.where("codeHash").is(com.agilityhub.core.identity.application.TokenService.digest(code))), HandoffCode.class))
                .singleElement().satisfies(stored -> assertThat(stored.usedAt()).isNull());
        grantAs("clubs-app", HandoffService.GRANT, code, HOST).andExpect(status().isOk());
    }

    @Test void T_01_13_R_01_02_productHostAnswersAnIdAuthorizationCodeAsAnUnknownHost() throws Exception {
        // The ID's authorization code: 404 on the product host; it still exchanges on the ID host.
        String authorization = authorizationCode("id-web");
        exchange("id-web", authorization, PRODUCT).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_HOST"));
        exchange("id-web", authorization, ID_HOST).andExpect(status().isOk());
    }
}
