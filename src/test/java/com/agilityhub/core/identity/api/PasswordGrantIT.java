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
        refresh(refreshValue(first), HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_REUSED"));
        assertThat(mongo.findAll(RefreshToken.class)).allMatch(token -> token.revokedAt() != null);
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
