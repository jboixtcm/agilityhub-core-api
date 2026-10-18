package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.TokenService;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PasswordGrantIT extends IdentityIntegrationSupport {
    @Test void T_02_10_suspendedClubBlocksLoginAndRefreshWhileOnboardingStillWorks() throws Exception {
        var tokens = login();
        mongo.updateFirst(Query.query(Criteria.where("_id").is("club-a")), new Update().set("status", "SUSPENDED"), com.agilityhub.core.platform.persistence.Club.class);
        configs.invalidate("club-a");
        mvc.perform(get("/api/v1/branding").header("Host", HOST)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED"));
        login(HOST, "admin@example.test", PASSWORD).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CLUB_SUSPENDED"));
        refresh(refreshValue(tokens), HOST, "clubs-app").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CLUB_SUSPENDED"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("club-a")), new Update().set("status", "ONBOARDING"), com.agilityhub.core.platform.persistence.Club.class);
        configs.invalidate("club-a");
        login(HOST, "admin@example.test", PASSWORD).andExpect(status().isOk());
        mvc.perform(get("/api/v1/branding").header("Host", HOST)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ONBOARDING"));
    }

    @Test void T_01_07_passwordIssuesSignedTenantClaimsAndOnlyHashedRefreshStorage() throws Exception {
        var result = login();
        var jwt = SignedJWT.parse(result.get("access_token").asText());
        var claims = jwt.getJWTClaimsSet();
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwt.getHeader().getKeyID()).isNotBlank();
        assertThat(claims.getSubject()).isEqualTo("account-a");
        assertThat(claims.getStringClaim("clubId")).isEqualTo("club-a");
        assertThat(claims.getStringClaim("memberId")).isEqualTo("member-a");
        assertThat(claims.getStringListClaim("roles")).containsExactly("ADMIN");
        assertThat(claims.getStringClaim("activeProfile")).isEqualTo("ADMIN");
        assertThat(claims.getStringClaim("locale")).isEqualTo("en");
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(result.get("expires_in").asLong()).isBetween(899L, 900L);
        assertThat(refreshValue(result)).hasSize(43);
        assertThat(mongo.getCollection("refresh_tokens").find().first().toJson())
                .doesNotContain(refreshValue(result)).contains(TokenService.digest(refreshValue(result)));
        assertThat(mongo.getCollection("accounts").find().first().getDate("lastLoginAt").toInstant()).isEqualTo(clock.instant());
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_01_07_wrongUnknownAndPasswordlessAccountsHaveIdenticalCredentialErrors() throws Exception {
        for (String email : new String[]{"admin@example.test", "unknown@example.test"}) {
            login(HOST, email, "wrong").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.length()").value(4)).andExpect(jsonPath("$.traceId").isNotEmpty());
        }
        mongo.updateFirst(new Query(), new Update().unset("passwordHash"), Account.class);
        login(HOST, "admin@example.test", PASSWORD).andExpect(status().isUnauthorized());
    }
    @Test void T_01_07_hostMembershipAndAccountStatesAreEnforced() throws Exception {
        login("b.example.test", "admin@example.test", PASSWORD).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_MEMBERSHIP"));
        login("unknown.example.test", "admin@example.test", PASSWORD).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_HOST"));
        for (var state : new Membership.Status[]{Membership.Status.SUSPENDED, Membership.Status.ERASED}) {
            mongo.updateFirst(new Query(), new Update().set("status", state), Membership.class);
            login(HOST, "admin@example.test", PASSWORD).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBERSHIP_SUSPENDED"));
        }
        for (var state : new Account.Status[]{Account.Status.BLOCKED, Account.Status.MERGED, Account.Status.ERASED}) {
            mongo.updateFirst(new Query(), new Update().set("status", state), Account.class);
            login(HOST, "admin@example.test", PASSWORD).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
            login(HOST, "admin@example.test", "wrong").andExpect(status().isUnauthorized());
        }
    }
    @Test void T_01_04_refreshRotatesReloadsRolesSlidesExpiryAndRevokesReusedFamily() throws Exception {
        var first = login();
        membership("club-a", Set.of(Role.MEMBER), null, null);
        var originalExpiry = mongo.findAll(RefreshToken.class).getFirst().expiresAt();
        clock.advance(Duration.ofMinutes(2));
        var next = readTokens(refresh(refreshValue(first), HOST, "clubs-app").andExpect(status().isOk())
                .andReturn().getResponse());
        assertThat(refreshValue(next)).isNotEqualTo(refreshValue(first));
        var claims = SignedJWT.parse(next.get("access_token").asText()).getJWTClaimsSet();
        assertThat(claims.getStringListClaim("roles")).containsExactly("MEMBER");
        assertThat(claims.getStringClaim("activeProfile")).isEqualTo("MEMBER");
        assertThat(claims.getClaim("memberId")).isNull();
        var records = mongo.findAll(RefreshToken.class);
        assertThat(records).hasSize(2);
        assertThat(records).allMatch(record -> record.expiresAt().equals(originalExpiry.plus(Duration.ofMinutes(2))));
        // INC-36: past auth.refreshReuseGraceSeconds (30) a reuse is a theft, as before.
        clock.advance(Duration.ofSeconds(31));
        refresh(refreshValue(first), HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_REUSED"));
        assertThat(mongo.findAll(RefreshToken.class)).allMatch(token -> token.revokedAt() != null);
        refresh(refreshValue(next), HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_EXPIRED"));
    }
    /**
     * INC-36 (S01 R-01-06, T-01-04, `auth.refreshReuseGraceSeconds` = 30): a client whose refresh answer was lost (an aborted
     * request, a closed tab) sends the rotated token again. Inside the grace it is accepted once more (round 2): it gets a sibling
     * of the live child, the child itself is not rotated, and nothing is revoked. A second reuse, or one after the grace, still
     * revokes the family.
     */
    @Test void INC_36_R_01_06_aRotatedTokenReusedInsideTheGraceIsAcceptedOnceAndKeepsTheFamily() throws Exception {
        var first = login();
        clock.advance(Duration.ofMinutes(1));
        var lost = readTokens(refresh(refreshValue(first), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        clock.advance(Duration.ofSeconds(29));
        var again = readTokens(refresh(refreshValue(first), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        assertThat(refreshValue(again)).isNotIn(refreshValue(first), refreshValue(lost));
        assertThat(sid(again)).isEqualTo(sid(lost));
        var family = mongo.findAll(RefreshToken.class);
        assertThat(family).hasSize(3).noneMatch(token -> token.revokedAt() != null);
        assertThat(liveHashes()).containsExactlyInAnyOrder(TokenService.digest(refreshValue(lost)), TokenService.digest(refreshValue(again)));
        assertThat(mongo.findAll(com.agilityhub.core.platform.persistence.SecurityEvent.class)).noneMatch(event -> event.type().name().equals("REFRESH_TOKEN_REUSED"));
        // The new sibling works; the grace is spent: the first token a third time is a reuse, and the whole family goes.
        var next = readTokens(refresh(refreshValue(again), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        refresh(refreshValue(first), HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_REUSED"));
        assertThat(mongo.findAll(RefreshToken.class)).allMatch(token -> token.revokedAt() != null);
        refresh(refreshValue(next), HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_EXPIRED"));
    }
    /**
     * INC-36 round 2, review #2 (a): the A/B/C ping-pong of a stolen token. The victim rotates A to B; the thief presents A inside
     * the grace. Before, that rotated the victim's B behind its back, B came back inside its own grace, and so on for ever. Now
     * the thief gets a sibling and B is left alone, so nothing is rotated behind a holder's back and there is no chain to follow:
     * the first chained reuse (A again, its grace spent) revokes the family, the thief's token and the victim's.
     */
    @Test void INC_36_R_01_06_T_01_04_thePingPongOfAStolenTokenIsRevokedAtTheFirstChainedReuse() throws Exception {
        var a = login();
        clock.advance(Duration.ofMinutes(1));
        var b = readTokens(refresh(refreshValue(a), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        clock.advance(Duration.ofSeconds(5));
        var thief = readTokens(refresh(refreshValue(a), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        assertThat(token(b).status()).as("the thief's grace does not rotate the victim's token").isEqualTo(RefreshToken.Status.ACTIVE);
        clock.advance(Duration.ofSeconds(5));
        // The victim's B is its own live token: a plain rotation, not a reuse that a grace would have to cover.
        var victim = readTokens(refresh(refreshValue(b), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        assertThat(token(b).replacedByHash()).isEqualTo(TokenService.digest(refreshValue(victim)));
        clock.advance(Duration.ofSeconds(5));
        refresh(refreshValue(a), HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_REUSED"));
        assertThat(mongo.findAll(RefreshToken.class)).hasSize(4).allMatch(t -> t.status() == RefreshToken.Status.REVOKED);
        refresh(refreshValue(thief), HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_EXPIRED"));
        refresh(refreshValue(victim), HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_EXPIRED"));
    }
    /**
     * INC-36 round 2, the chain guard: a rotated token is graced only while the child it was replaced by is still live. Once that
     * child has been rotated in turn, the old token coming back, even inside its grace, is a reuse (before, the grace rotated the
     * family's newest token to the caller and the chain went on).
     */
    @Test void INC_36_R_01_06_T_01_04_aTokenWhoseChildHasMovedOnGetsNoGrace() throws Exception {
        var a = login();
        clock.advance(Duration.ofMinutes(1));
        var b = readTokens(refresh(refreshValue(a), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        clock.advance(Duration.ofSeconds(5));
        refresh(refreshValue(b), HOST, "clubs-app").andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(5));
        refresh(refreshValue(a), HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_REUSED"));
        assertThat(mongo.findAll(RefreshToken.class)).hasSize(3).allMatch(t -> t.status() == RefreshToken.Status.REVOKED);
    }
    /**
     * INC-36 round 2, review #2 (b): two holders of one token (two tabs sharing the cookie, or two clients of one session) refresh
     * it at once and their answers arrive in either order. Whichever token the shared cookie jar keeps (the answer that arrived
     * last), the next refresh does not revoke the session, even when both tabs refresh at once again; and two holders that each
     * keep their own token both refresh later, in either order.
     */
    @Test void INC_36_R_01_06_T_01_04_twoHoldersOfOneTokenKeepTheirSessionWhicheverAnswerArrivesLast() throws Exception {
        for (boolean childArrivesLast : new boolean[]{true, false}) {
            var shared = login();
            clock.advance(Duration.ofMinutes(1));
            var child = readTokens(refresh(refreshValue(shared), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
            clock.advance(Duration.ofMillis(300));
            var sibling = readTokens(refresh(refreshValue(shared), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
            String jar = refreshValue(childArrivesLast ? child : sibling);
            // 15 minutes later both tabs refresh with the jar's token at once: one rotation and one grace, both 200.
            clock.advance(Duration.ofMinutes(15));
            var tab1 = readTokens(refresh(jar, HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
            clock.advance(Duration.ofMillis(300));
            var tab2 = readTokens(refresh(jar, HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
            // And again: the session survives every round of simultaneous refreshes, whichever answer the jar keeps.
            clock.advance(Duration.ofMinutes(15));
            String jar2 = refreshValue(childArrivesLast ? tab2 : tab1);
            refresh(jar2, HOST, "clubs-app").andExpect(status().isOk());
            clock.advance(Duration.ofMillis(300));
            refresh(jar2, HOST, "clubs-app").andExpect(status().isOk());
            assertThat(familyOf(shared)).noneMatch(t -> t.status() == RefreshToken.Status.REVOKED)
                    .as("a family never holds more than two live tokens").filteredOn(t -> t.status() == RefreshToken.Status.ACTIVE).hasSize(2);
        }
        var shared = login();
        clock.advance(Duration.ofMinutes(1));
        var first = readTokens(refresh(refreshValue(shared), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        var second = readTokens(refresh(refreshValue(shared), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        clock.advance(Duration.ofMinutes(15));
        var secondNext = readTokens(refresh(refreshValue(second), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        clock.advance(Duration.ofMinutes(1));
        var firstNext = readTokens(refresh(refreshValue(first), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        clock.advance(Duration.ofMinutes(15));
        refresh(refreshValue(firstNext), HOST, "clubs-app").andExpect(status().isOk());
        refresh(refreshValue(secondNext), HOST, "clubs-app").andExpect(status().isOk());
        assertThat(familyOf(shared)).noneMatch(t -> t.status() == RefreshToken.Status.REVOKED);
        assertThat(mongo.findAll(com.agilityhub.core.platform.persistence.SecurityEvent.class)).noneMatch(event -> event.type().name().equals("REFRESH_TOKEN_REUSED"));
    }
    /**
     * INC-36 round 2, the cap: a grace leaves the family with only the presented token's child and the new sibling. The leftover
     * of an earlier race (a token the jar no longer holds) is retired with its grace spent, so presenting it later is a reuse.
     */
    @Test void INC_36_R_01_06_T_01_04_aGraceRetiresTheLeftoverOfAnEarlierRaceAndItsReuseRevokesTheFamily() throws Exception {
        var shared = login();
        clock.advance(Duration.ofMinutes(1));
        var leftover = readTokens(refresh(refreshValue(shared), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        var kept = readTokens(refresh(refreshValue(shared), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        clock.advance(Duration.ofMinutes(15));
        var child = readTokens(refresh(refreshValue(kept), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        assertThat(liveHashes()).hasSize(2);
        var sibling = readTokens(refresh(refreshValue(kept), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        assertThat(liveHashes()).containsExactlyInAnyOrder(TokenService.digest(refreshValue(child)), TokenService.digest(refreshValue(sibling)));
        assertThat(token(leftover).status()).isEqualTo(RefreshToken.Status.ROTATED);
        clock.advance(Duration.ofSeconds(1));
        refresh(refreshValue(leftover), HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_REUSED"));
        assertThat(mongo.findAll(RefreshToken.class)).allMatch(t -> t.status() == RefreshToken.Status.REVOKED);
    }
    private RefreshToken token(com.fasterxml.jackson.databind.JsonNode response) {
        return mongo.findOne(Query.query(Criteria.where("tokenHash").is(TokenService.digest(refreshValue(response)))), RefreshToken.class);
    }
    private java.util.List<RefreshToken> familyOf(com.fasterxml.jackson.databind.JsonNode response) {
        return mongo.find(Query.query(Criteria.where("familyId").is(token(response).familyId())), RefreshToken.class);
    }
    private java.util.List<String> liveHashes() {
        return mongo.findAll(RefreshToken.class).stream().filter(t -> t.status() == RefreshToken.Status.ACTIVE).map(RefreshToken::tokenHash).toList();
    }
    private static String sid(com.fasterxml.jackson.databind.JsonNode response) throws Exception {
        return SignedJWT.parse(response.get("access_token").asText()).getJWTClaimsSet().getStringClaim("sid");
    }
    @Test void INC_36_R_01_06_aReuseAfterTheGraceRevokesTheFamily() throws Exception {
        var first = login();
        var next = readTokens(refresh(refreshValue(first), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        clock.advance(Duration.ofSeconds(30));
        refresh(refreshValue(first), HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_REUSED"));
        assertThat(mongo.findAll(RefreshToken.class)).hasSize(2).allMatch(token -> token.revokedAt() != null);
        refresh(refreshValue(next), HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_EXPIRED"));
    }
    @Test void T_01_07_refreshRejectsOtherTenantClientUnknownExpiredAndRevokedVersion() throws Exception {
        String refresh = refreshValue(login());
        refresh(refresh, "b.example.test", "clubs-app").andExpect(status().isBadRequest());
        refresh(refresh, HOST, "clubs-admin").andExpect(status().isBadRequest());
        refresh("unknown", HOST, "clubs-app").andExpect(status().isBadRequest());
        mongo.updateFirst(new Query(), new Update().set("security.tokenFamilyVersion", 1), Account.class);
        refresh(refresh, HOST, "clubs-app").andExpect(status().isBadRequest());
        mongo.updateFirst(new Query(), new Update().unset("security"), Account.class);
        clock.advance(Duration.ofDays(31));
        refresh(refresh, HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_EXPIRED"));
    }
    @Test void T_01_07_invalidOAuthRequestsUseOnlyCatalogErrors() throws Exception {
        for (String grant : new String[]{"unsupported", "password"}) {
            mvc.perform(post("/oauth2/token").header("Host", HOST).param("grant_type", grant))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(post("/oauth2/token").header("Host", HOST).param("grant_type", "password", "password"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/oauth2/token").header("Host", HOST).param("grant_type", "password")
                .param("client_id", "unknown").param("username", "admin@example.test").param("password", PASSWORD))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        for (String field : new String[]{"scope", "client_secret"}) {
            mvc.perform(post("/oauth2/token").header("Host", HOST).param("grant_type", "password").param(field, "unexpected"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/oauth2/token").header("Host", HOST).param("grant_type", "password").param("username", "a", "b"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/oauth2/token").header("Host", HOST).param("grant_type", "password").param("username", " "))
                .andExpect(status().isBadRequest());
    }
    @Test void T_01_01_uniqueEmailAndMembershipIndexesAndTenantRepositoryIsolation() {
        var account = accounts.findById("account-a").orElseThrow();
        assertThatThrownBy(() -> accounts.save(new Account("duplicate", " ADMIN@EXAMPLE.TEST ", "Example", "ca", null,
                Set.of(), Account.Status.ACTIVE, null, java.util.Map.of(), false, clock.instant()))).isInstanceOf(DuplicateKeyException.class);
        try (var scope = TenantContext.open("club-a")) {
            assertThatThrownBy(() -> memberships.insert(new Membership("duplicate", account.id(), "club-a", null,
                    Set.of(Role.MEMBER), Membership.Status.ACTIVE, null))).isInstanceOf(DuplicateKeyException.class);
        }
        try (var scope = TenantContext.open("club-b")) {
            assertThat(memberships.findByAccountId(account.id())).isEmpty();
            assertThat(memberships.findById("membership-club-a")).isEmpty();
            assertThat(memberships.deleteById("membership-club-a")).isFalse();
            assertThatThrownBy(() -> memberships.insert(new Membership("cross", account.id(), "club-a", null,
                    Set.of(Role.MEMBER), Membership.Status.ACTIVE, null))).hasMessage("TENANT_MISMATCH");
        }
    }
}
