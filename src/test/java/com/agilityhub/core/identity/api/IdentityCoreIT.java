package com.agilityhub.core.identity.api;

import com.agilityhub.core.clubs.messaging.application.FakeEmailSender;
import com.agilityhub.core.identity.application.*;
import com.agilityhub.core.identity.domain.IdentityEvent;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.persistence.Parameter;
import com.agilityhub.core.platform.persistence.SecurityEvent;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.OutboxDispatcher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.DomainEventRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IdentityCoreIT extends IdentityIntegrationSupport {
    @Autowired AccountService accountService;
    @Autowired MembershipService membershipService;
    @Autowired MagicLinkService magicLinks;
    @Autowired TokenService tokens;
    @Autowired IdentityTransactions transactions;
    @Autowired EventPublisher events;
    @Autowired OutboxDispatcher dispatcher;
    @MockitoBean CompromisedPasswords compromised;

    String bearer(JsonNode token) { return "Bearer " + token.path("access_token").asText(); }
    String linkToken() throws Exception {
        awaitMail();
        var mail = ((FakeEmailSender) emailSender).lastTo("admin@example.test");
        var matcher = Pattern.compile("https://[^\\s]+[?]t=([A-Za-z0-9_-]{43})").matcher(mail.text());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
    org.springframework.test.web.servlet.ResultActions magic(String value, String client, String host) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Host", host).contentType("application/x-www-form-urlencoded")
                .param("grant_type", MagicLinkService.GRANT).param("client_id", client).param("token", value));
    }
    void requestMagic(String email, String host) throws Exception {
        mvc.perform(post("/api/v1/auth/magic-link").header("Host", host).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("email", email, "purpose", "LOGIN", "client_id", "clubs-app"))))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
    }
    void createMagic(MagicLinkToken.Purpose purpose) {
        try (var scope = TenantContext.open("club-a")) {
            magicLinks.createAndSend("admin@example.test", purpose, "clubs-app", null, HOST, "203.0.113.50", "Mozilla/5.0 Firefox/1");
        }
    }
    List<DomainEventRecord> events(String type) { return mongo.find(Query.query(Criteria.where("type").is(type)), DomainEventRecord.class); }

    @Test void T_01_01_getOrCreateNormalizesIsConcurrentIdempotentAndWritesOneOutboxEvent() throws Exception {
        long before = events("AccountCreated").size();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.<Callable<Account>>of(
                    () -> accountService.getOrCreate("  NEW@EXAMPLE.TEST ", "Example", "en", Account.Source.SIGNUP),
                    () -> accountService.getOrCreate("new@example.test", "Example", "en", Account.Source.SIGNUP)));
            assertThat(results.get(0).get().id()).isEqualTo(results.get(1).get().id());
        }
        var account = accountService.getOrCreate("new@example.test", "Ignored name", "es", Account.Source.CONSOLE);
        assertThat(account.name()).isEqualTo("Example"); assertThat(account.locale()).isEqualTo("en");
        assertThat(account.createdSource()).isEqualTo(Account.Source.SIGNUP); assertThat(account.onboardingPending()).isFalse();
        assertThat(events("AccountCreated")).hasSize((int) before + 1);
        assertThat(events("AccountCreated").getLast().eventJson()).doesNotContain("new@example.test");
        for (Account.Source source : List.of(Account.Source.IMPORT_LEARN, Account.Source.MIGRATION)) {
            assertThat(accountService.getOrCreate(source.name() + "@example.test", "Example", "ca", source).onboardingPending()).isTrue();
        }
        assertThatThrownBy(() -> accountService.getOrCreate("bad@example.test", "Example", "fr", Account.Source.CONSOLE)).hasMessage("LOCALE_NOT_SUPPORTED");
        assertThatThrownBy(() -> accountService.getOrCreate("bad@example.test", " ", "en", Account.Source.CONSOLE)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> accountService.getOrCreate("bad@example.test", "Example", "en", null)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> transactions.run(() -> {
            accountService.getOrCreate("rollback@example.test", "Example", "en", Account.Source.CONSOLE);
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(accounts.findByEmail("rollback@example.test")).isEmpty();
    }

    @Test void T_01_08_neutralRequestDeliversSystemMailAfterCommitAndRedeemsExactlyOnce() throws Exception {
        requestMagic("unknown@example.test", HOST); awaitMail();
        assertThat(((FakeEmailSender) emailSender).messages()).isEmpty();
        requestMagic("admin@example.test", "b.example.test"); awaitMail();
        assertThat(((FakeEmailSender) emailSender).messages()).isEmpty();
        requestMagic("admin@example.test", HOST);
        String value = linkToken();
        assertThat(java.util.Base64.getUrlDecoder().decode(value)).hasSize(32);
        var stored = mongo.findAll(MagicLinkToken.class).getFirst();
        assertThat(stored.tokenHash()).isEqualTo(TokenService.digest(value));
        assertThat(stored.expiresAt()).isEqualTo(clock.instant().plusSeconds(900));
        assertThat(mongo.getCollection("magic_link_tokens").find().first().toJson()).doesNotContain(value);
        var email = ((FakeEmailSender) emailSender).lastTo("admin@example.test");
        assertThat(email.text()).contains("https://" + HOST + "/activacio?t=");
        assertThat(events("MagicLinkRequested").getLast().eventJson()).doesNotContain(value, stored.tokenHash());
        assertThat(mongo.getCollection("notifications").find(new org.bson.Document("code", "N-25")).first().getString("status")).isEqualTo("SENT");
        magic(value, "clubs-admin", HOST).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MAGIC_LINK_INVALID"));
        magic(value, "clubs-app", "b.example.test").andExpect(status().isBadRequest());
        var result = readTokens(magic(value, "clubs-app", HOST).andExpect(status().isOk())
                .andExpect(cookie().exists(RefreshCookies.NAME)).andExpect(jsonPath("$.refresh_token").doesNotExist()).andReturn().getResponse());
        mvc.perform(get("/api/v1/me").header("Host", HOST).header("Authorization", bearer(result)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.account.emailVerifiedAt").value(clock.instant().toString()))
                .andExpect(jsonPath("$.account.hasPassword").value(true));
        magic(value, "clubs-app", HOST).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MAGIC_LINK_INVALID"));
        assertThat(mongo.findAll(SecurityEvent.class)).anyMatch(e -> e.type().name().equals("MAGIC_LINK_INVALID"));
    }

    @Test void T_01_03_magicLinksCapThreePerPurposeAcrossClubsAndExpireOnTheInjectedClock() throws Exception {
        for (int n = 0; n < 4; n++) { createMagic(MagicLinkToken.Purpose.LOGIN); clock.advance(Duration.ofSeconds(1)); }
        createMagic(MagicLinkToken.Purpose.RESET);
        assertThat(mongo.findAll(MagicLinkToken.class).stream().filter(t -> t.purpose() == MagicLinkToken.Purpose.LOGIN && t.usedAt() == null)).hasSize(3);
        assertThat(mongo.findAll(MagicLinkToken.class).stream().filter(t -> t.purpose() == MagicLinkToken.Purpose.RESET && t.usedAt() == null)).hasSize(1);
        String value = linkToken();
        clock.advance(Duration.ofMinutes(15));
        magic(value, "clubs-app", HOST).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MAGIC_LINK_INVALID"));
        createMagic(MagicLinkToken.Purpose.WELCOME); createMagic(MagicLinkToken.Purpose.ACCESS_RESEND); createMagic(MagicLinkToken.Purpose.RECOGNITION);
        assertThat(mongo.findAll(MagicLinkToken.class).stream().filter(t -> t.purpose() == MagicLinkToken.Purpose.WELCOME || t.purpose() == MagicLinkToken.Purpose.ACCESS_RESEND))
                .allMatch(t -> t.expiresAt().equals(t.createdAt().plus(Duration.ofDays(7))));
        assertThat(mongo.getCollection("notifications").find(new org.bson.Document("code", "N-27")).first()).isNotNull();
    }

    @Test void T_01_08_unknownClientsUnsafeRedirectsBlockedAccountsAndAbsentMembershipNeverSend() {
        for (String client : List.of("unknown", "clubs-app")) {
            try (var scope = TenantContext.open("club-a")) {
                magicLinks.createAndSend("admin@example.test", MagicLinkToken.Purpose.LOGIN, client, "https://attacker.example.test", HOST, null, null);
                magicLinks.createAndSend("admin@example.test", MagicLinkToken.Purpose.LOGIN, client, null, "unknown.example.test", null, null);
            }
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is("account-a")), new Update().set("status", "BLOCKED"), Account.class);
        createMagic(MagicLinkToken.Purpose.LOGIN);
        assertThat(((FakeEmailSender) emailSender).messages()).isEmpty();
        assertThat(mongo.findAll(MagicLinkToken.class)).isEmpty();
    }

    @Test void T_01_06_passwordLockoutPersistsAndMagicLinkRemainsAvailable() throws Exception {
        for (int round = 0; round < 3; round++) {
            for (int n = 0; n < 4; n++) { login(HOST, "admin@example.test", "wrong").andExpect(status().isUnauthorized()); }
            login(HOST, "admin@example.test", "wrong").andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("LOGIN_LOCKED")).andExpect(header().string("Retry-After", Long.toString((15L << round) * 60)));
            login(HOST, "admin@example.test", PASSWORD).andExpect(status().isTooManyRequests());
            if (round < 2) { clock.advance(Duration.ofMinutes(15L << round)); }
        }
        assertThat(mongo.findAll(SecurityEvent.class)).anyMatch(e -> e.type().name().equals("LOGIN_LOCKED"));
        requestMagic("admin@example.test", HOST);
        magic(linkToken(), "clubs-app", HOST).andExpect(status().isOk());
        assertThat(accounts.findById("account-a").orElseThrow().lockout().locked(clock.instant())).isFalse();
        login();
    }

    @Test void T_01_04_simultaneousRefreshOnlyRotatesOnceAndCommitsFamilyRevocationOnReuse() throws Exception {
        String value = refreshValue(login());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CyclicBarrier(2);
            Callable<String> attempt = () -> {
                try (var scope = TenantContext.open("club-a")) {
                    gate.await();
                    try { tokens.refresh(value, "clubs-app"); return "OK"; }
                    catch (ApiException e) { return e.code().name(); }
                }
            };
            var results = pool.invokeAll(List.of(attempt, attempt));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder("OK", "REFRESH_REUSED");
        }
        assertThat(mongo.findAll(RefreshToken.class)).hasSize(2).allMatch(t -> t.status() == RefreshToken.Status.REVOKED);
    }

    @Test void T_01_09_passwordChangePreservesCurrentFamilyRevokesOtherDevicesAndSendsN26() throws Exception {
        var first = login(); var other = login();
        mvc.perform(put("/api/v1/me/password").header("Host", HOST).header("Authorization", bearer(first)).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("current", PASSWORD, "new", "Fictional new password", "repeat", "Fictional new password"))))
                .andExpect(status().isOk());
        var account = accounts.findById("account-a").orElseThrow();
        assertThat(account.familyVersion()).isEqualTo(1); assertThat(account.passwordHash()).startsWith("$argon2id$");
        assertThat(passwords.verify("Fictional new password", account.passwordHash())).isTrue();
        assertThat(account.security().passwordChangedAt()).isEqualTo(clock.instant());
        verify(compromised).contains("Fictional new password");
        assertThat(events("PasswordChanged")).isNotEmpty();
        assertThat(((FakeEmailSender) emailSender).lastTo(account.email()).text()).doesNotContain("Fictional new password");
        refresh(refreshValue(other), HOST, "clubs-app").andExpect(status().isBadRequest());
        refresh(refreshValue(first), HOST, "clubs-app").andExpect(status().isOk());
    }

    @Test void T_01_09_optionalPasswordCurrentMismatchMinimumLengthEmailAndCompromisedPolicy() throws Exception {
        var first = login();
        var request = put("/api/v1/me/password").header("Host", HOST).header("Authorization", bearer(first)).contentType("application/json");
        mvc.perform(request.content("{\"new\":\"fictional long password\",\"repeat\":\"fictional long password\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(request.content(mapper.writeValueAsString(Map.of("current", "wrong", "new", "long password", "repeat", "long password"))))
                .andExpect(status().isUnauthorized());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("account-a")), new Update().unset("passwordHash"), Account.class);
        for (String[] sample : List.of(new String[]{"long password", "different", "PASSWORD_MISMATCH"},
                new String[]{"short", "short", "PASSWORD_TOO_SHORT"}, new String[]{"admin@example.test", "admin@example.test", "PASSWORD_COMPROMISED"})) {
            mvc.perform(request.content(mapper.writeValueAsString(Map.of("new", sample[0], "repeat", sample[1]))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(sample[2]));
        }
        when(compromised.contains("Fictional leaked password")).thenReturn(true);
        mvc.perform(request.content("{\"new\":\"Fictional leaked password\",\"repeat\":\"Fictional leaked password\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_COMPROMISED"));
        mvc.perform(request.content("{\"new\":\"Fictional safe password\",\"repeat\":\"Fictional safe password\"}"))
                .andExpect(status().isOk());
        assertThat(accounts.findById("account-a").orElseThrow().passwordHash()).isNotNull();
    }

    @Test void T_01_10_profilesRememberOnlyWhenRequestedAndRefreshFallsBackWhenRoleRemoved() throws Exception {
        membership("club-a", Set.of(Role.MEMBER, Role.INSTRUCTOR), null, "member-a");
        var first = login();
        mvc.perform(put("/api/v1/me/profile").header("Host", HOST).header("Authorization", bearer(first)).contentType("application/json")
                .content("{\"activeProfile\":\"ADMIN\",\"remember\":true}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PROFILE_NOT_AVAILABLE"));
        String response = mvc.perform(put("/api/v1/me/profile").header("Host", HOST).header("Authorization", bearer(first)).contentType("application/json")
                        .content("{\"activeProfile\":\"INSTRUCTOR\",\"remember\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String access = mapper.readTree(response).path("access_token").asText();
        assertThat(SignedJWT.parse(access).getJWTClaimsSet().getStringClaim("activeProfile")).isEqualTo("INSTRUCTOR");
        mvc.perform(get("/api/v1/me").header("Host", HOST).header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.membership.rememberProfile").value(true))
                .andExpect(jsonPath("$.membership.defaultProfile").value("INSTRUCTOR"));
        assertThat(SignedJWT.parse(login().path("access_token").asText()).getJWTClaimsSet().getStringClaim("activeProfile")).isEqualTo("INSTRUCTOR");
        var refreshed = readTokens(refresh(refreshValue(first), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        assertThat(SignedJWT.parse(refreshed.path("access_token").asText()).getJWTClaimsSet().getStringClaim("activeProfile")).isEqualTo("INSTRUCTOR");
        mvc.perform(put("/api/v1/me/profile").header("Host", HOST).header("Authorization", bearer(refreshed)).contentType("application/json")
                        .content("{\"activeProfile\":\"MEMBER\",\"remember\":false}"))
                .andExpect(status().isOk());
        try (var scope = TenantContext.open("club-a")) {
            assertThat(memberships.findByAccountId("account-a").orElseThrow().defaultProfile()).isNull();
            membershipService.setRoles("account-a", Set.of(Role.INSTRUCTOR));
        }
        var fallback = readTokens(refresh(refreshValue(refreshed), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        assertThat(SignedJWT.parse(fallback.path("access_token").asText()).getJWTClaimsSet().getStringClaim("activeProfile")).isEqualTo("INSTRUCTOR");
    }

    @Test void T_01_05_membershipServiceIsTenantBoundAndSuspendResumeCannotRestoreErasedMemberships() throws Exception {
        long before = events("MembershipChanged").size();
        try (var scope = TenantContext.open("club-a")) {
            membershipService.setRoles("account-a", Set.of(Role.ADMIN));
            assertThat(events("MembershipChanged")).hasSize((int) before);
            membershipService.suspend("account-a");
        }
        login(HOST, "admin@example.test", PASSWORD).andExpect(status().isForbidden());
        try (var scope = TenantContext.open("club-a")) {
            membershipService.resume("account-a");
            membershipService.setRoles("account-a", Set.of());
            assertThatThrownBy(() -> membershipService.resume("account-a")).hasMessage("MEMBERSHIP_SUSPENDED");
            membershipService.setRoles("account-a", Set.of(Role.MEMBER)); membershipService.resume("account-a");
        }
        try (var scope = TenantContext.open("club-b")) {
            assertThatThrownBy(() -> membershipService.suspend("account-a")).hasMessage("NO_MEMBERSHIP");
            membershipService.setRoles("account-a", Set.of(Role.INSTRUCTOR));
        }
        mongo.updateFirst(Query.query(Criteria.where("clubId").is("club-a")), new Update().set("status", "ERASED"), Membership.class);
        try (var scope = TenantContext.open("club-a")) {
            assertThatThrownBy(() -> membershipService.resume("account-a")).hasMessage("MEMBERSHIP_SUSPENDED");
            assertThatThrownBy(() -> membershipService.setRoles("account-a", Set.of(Role.MEMBER))).hasMessage("MEMBERSHIP_SUSPENDED");
        }
        assertThatThrownBy(() -> membershipService.setRoles("account-a", Set.of(Role.MEMBER))).hasMessage("NO_MEMBERSHIP");
    }

    @Test void T_01_17_accountPatchAndDeviceSessionsRespectEveryRoleOwnerAndHost() throws Exception {
        membership("club-b", Set.of(Role.MEMBER), null, "member-b");
        var first = login();
        mvc.perform(patch("/api/v1/me").header("Host", HOST).header("Authorization", bearer(first)).contentType("application/json")
                        .content("{\"locale\":\"es\",\"name\":\"Changed Example\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.account.name").value("Changed Example"))
                .andExpect(jsonPath("$.account.locale").value("es"));
        mvc.perform(patch("/api/v1/me").header("Host", HOST).header("Authorization", bearer(first)).contentType("application/json").content("{\"locale\":\"fr\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LOCALE_NOT_SUPPORTED"));
        mvc.perform(patch("/api/v1/me").header("Host", HOST).header("Authorization", bearer(first)).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        var other = readTokens(login("b.example.test", "admin@example.test", PASSWORD).andExpect(status().isOk()).andReturn().getResponse());
        for (String role : List.of("MEMBER", "INSTRUCTOR", "ADMIN")) {
            mvc.perform(get("/api/v1/me/sessions").header("Host", HOST).with(jwt().jwt(j -> j.subject("account-a").claim("clubId", "club-a"))
                            .authorities(() -> "ROLE_" + role)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].clubId").value("club-a"));
        }
        var list = mapper.readTree(mvc.perform(get("/api/v1/me/sessions").header("Host", HOST).header("Authorization", bearer(first)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.toString()).doesNotContain("tokenHash", "passwordHash", "security", "replacedByHash", refreshValue(first));
        String family = list.get(0).path("id").asText();
        for (var call : List.of(get("/api/v1/me/sessions"), delete("/api/v1/me/sessions/" + family),
                post("/oauth2/revoke").contentType("application/json").content("{\"token\":\"fictional\"}"),
                patch("/api/v1/me").contentType("application/json").content("{}"),
                put("/api/v1/me/profile").contentType("application/json").content("{\"activeProfile\":\"ADMIN\",\"remember\":false}"),
                put("/api/v1/me/password").contentType("application/json").content("{\"new\":\"fictional\",\"repeat\":\"fictional\"}"))) {
            mvc.perform(call).andExpect(status().isUnauthorized());
            mvc.perform(call.header("Host", "b.example.test").header("Authorization", bearer(first)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
        }
        mvc.perform(delete("/api/v1/me/sessions/" + family).header("Host", "b.example.test").header("Authorization", bearer(other))).andExpect(status().isOk());
        refresh(refreshValue(first), HOST, "clubs-app").andExpect(status().isOk());
        mvc.perform(delete("/api/v1/me/sessions/" + family).header("Host", HOST).header("Authorization", bearer(first))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/me/sessions/" + family).header("Host", HOST).header("Authorization", bearer(first))).andExpect(status().isOk());
        assertThat(events("SessionRevoked")).isNotEmpty();
    }

    @Test void T_01_10_revokeIsIdempotentAndCannotRevokeAnotherAccount() throws Exception {
        var first = login();
        var request = post("/oauth2/revoke").header("Host", HOST).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("token", refreshValue(first))));
        accountService.getOrCreate("other@example.test", "Other Example", "en", Account.Source.CONSOLE);
        String other = accounts.findByEmail("other@example.test").orElseThrow().id();
        try (var scope = TenantContext.open("club-a")) { membershipService.setRoles(other, Set.of(Role.MEMBER)); }
        mvc.perform(request.with(jwt().jwt(j -> j.subject(other).claim("clubId", "club-a")))).andExpect(status().isOk());
        mvc.perform(request.header("Authorization", bearer(first))).andExpect(status().isOk());
        mvc.perform(request.header("Authorization", bearer(first))).andExpect(status().isOk());
        refresh(refreshValue(first), HOST, "clubs-app").andExpect(status().isBadRequest());
    }

    @Test void T_01_06_maximumSessionsRevokesOldestAndDeviceMetadataIsBounded() throws Exception {
        String first = null;
        for (int n = 0; n < 11; n++) {
            try (var scope = TenantContext.open("club-a")) {
                var issued = tokens.password("admin@example.test", PASSWORD, "clubs-app", "Mozilla/5.0 (Windows) Chrome/1 credential=never-store-this");
                if (n == 0) { first = issued.refresh().getTokenValue(); }
            }
            clock.advance(Duration.ofSeconds(1));
        }
        try (var scope = TenantContext.open("club-a")) {
            assertThat(tokens.sessions("account-a")).hasSize(10).allMatch(t -> t.deviceLabel().equals("Chrome / Windows"));
        }
        refresh(first, HOST, "clubs-app").andExpect(status().isBadRequest());
        assertThat(mongo.findAll(RefreshToken.class).toString()).doesNotContain("credential");
    }

    @Test void T_01_16_erasureOutboxRevokesEveryClubFamilyAndRejectsLiveAccessImmediately() throws Exception {
        mongo.remove(new Query(), DomainEventRecord.class);
        membership("club-b", Set.of(Role.MEMBER), null, "member-b");
        var first = login();
        var other = readTokens(login("b.example.test", "admin@example.test", PASSWORD).andExpect(status().isOk()).andReturn().getResponse());
        transactions.run(() -> events.publish(new IdentityEvent(IdentityEvent.Kind.AccountErasureRequested, null, "account-a", clock.instant(), Map.of("accountId", "account-a"))));
        dispatcher.dispatch(); dispatcher.dispatch();
        assertThat(accounts.findById("account-a").orElseThrow().familyVersion()).isEqualTo(1);
        assertThat(mongo.findAll(RefreshToken.class)).allMatch(token -> token.revokedAt() != null);
        refresh(refreshValue(first), HOST, "clubs-app").andExpect(status().isBadRequest());
        refresh(refreshValue(other), "b.example.test", "clubs-app").andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me").header("Host", HOST).header("Authorization", bearer(first))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/missing").header("Host", HOST).header("Authorization", bearer(first))).andExpect(status().isForbidden());
    }

    @Test void T_01_17_globalMagicSessionHasNoClubClaimsAndListsOnlyItsAccountSessions() throws Exception {
        String host = "id.agilitydoghub.com";
        magicLinks.createAndSend("admin@example.test", MagicLinkToken.Purpose.LOGIN, "id-web", null, host, null, null);
        var global = readTokens(magic(linkToken(), "id-web", host).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(SignedJWT.parse(global.path("access_token").asText()).getJWTClaimsSet().getClaim("clubId")).isNull();
        mvc.perform(get("/api/v1/me").header("Host", host).header("Authorization", bearer(global)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.membership").doesNotExist()).andExpect(jsonPath("$.features").isEmpty());
        mvc.perform(get("/api/v1/me").header("Host", HOST).header("Authorization", bearer(global))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/sessions").header("Host", host).header("Authorization", bearer(global)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        refresh(refreshValue(global), host, "id-web").andExpect(status().isOk());
    }

    @Test void T_01_16_suspensionRevokesOnlyThatClubsSessionsAndResumeRequiresNewLogin() throws Exception {
        membership("club-b", Set.of(Role.MEMBER), null, "member-b");
        var first = login();
        var other = readTokens(login("b.example.test", "admin@example.test", PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse());
        try (var scope = TenantContext.open("club-a")) { membershipService.suspend("account-a"); }
        refresh(refreshValue(first), HOST, "clubs-app")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBERSHIP_SUSPENDED"));
        refresh(refreshValue(other), "b.example.test", "clubs-app").andExpect(status().isOk());
        try (var scope = TenantContext.open("club-a")) { membershipService.resume("account-a"); }
        refresh(refreshValue(first), HOST, "clubs-app")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_EXPIRED"));
        login();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Role.class)
    void T_01_17_accountMutationsAndSessionRevocationAreAvailableToEveryClubRole(Role role) throws Exception {
        membership("club-a", Set.of(role), null, "member-a");
        var first = login();
        var other = login();
        mvc.perform(patch("/api/v1/me").header("Host", HOST).header("Authorization", bearer(first)).contentType("application/json")
                .content("{\"name\":\"Role Example\",\"locale\":\"es\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.account.name").value("Role Example"));
        mvc.perform(put("/api/v1/me/profile").header("Host", HOST).header("Authorization", bearer(first)).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("activeProfile", role.name(), "remember", true))))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/me/password").header("Host", HOST).header("Authorization", bearer(first)).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("current", PASSWORD, "new", "Fictional role password", "repeat", "Fictional role password"))))
                .andExpect(status().isOk());
        var renewed = readTokens(refresh(refreshValue(first), HOST, "clubs-app")
                .andExpect(status().isOk()).andReturn().getResponse());
        assertThat(SignedJWT.parse(renewed.path("access_token").asText()).getJWTClaimsSet().getStringClaim("locale")).isEqualTo("es");
        mvc.perform(post("/oauth2/revoke").header("Host", HOST).header("Authorization", bearer(renewed)).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("token", refreshValue(other))))).andExpect(status().isOk());
        String family = SignedJWT.parse(renewed.path("access_token").asText()).getJWTClaimsSet().getStringClaim("sid");
        mvc.perform(delete("/api/v1/me/sessions/" + family).header("Host", HOST).header("Authorization", bearer(renewed)))
                .andExpect(status().isOk());
        refresh(refreshValue(renewed), HOST, "clubs-app").andExpect(status().isBadRequest());
    }
}
