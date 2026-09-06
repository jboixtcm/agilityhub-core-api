package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.*;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OidcIT extends IdentityIntegrationSupport {
    static final String ID = "https://id.agilitydoghub.com";
    static final String VERIFIER = "a".repeat(64);
    static final String LEARN_SECRET = UUID.randomUUID().toString();
    static final String MASTER = OIDC_MASTER;
    @DynamicPropertySource static void oidcProperties(DynamicPropertyRegistry registry) {
        registry.add("OIDC_LEARN_CLIENT_SECRET", () -> LEARN_SECRET);
        registry.add("core.oidc.master-key", () -> MASTER);
        registry.add("OIDC_CLUBS_APP_REDIRECT_URI", () -> "https://app.example.test/oidc/callback");
    }
    @Autowired OidcService oidc;
    @Autowired SigningKeys signing;
    @Autowired SigningKeyRepository keyRepository;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired org.springframework.security.oauth2.jwt.JwtDecoder decoder;
    @Autowired org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository registered;
    String redirect(String client) { return registered.findByClientId(client).getRedirectUris().iterator().next(); }
    MockHttpServletRequestBuilder authorize(String client, String scopes) {
        return get("/oauth2/authorize").header("Host", "id.agilitydoghub.com").secure(true)
                .param("response_type", "code").param("client_id", client).param("redirect_uri", redirect(client))
                .param("scope", scopes).param("state", "state & equals=preserved").param("nonce", "test-nonce")
                .param("code_challenge", TokenService.digest(VERIFIER)).param("code_challenge_method", "S256");
    }
    String param(String uri, String key) {
        return org.springframework.web.util.UriComponentsBuilder.fromUriString(uri).build().getQueryParams().getFirst(key);
    }
    String loginAccess() throws Exception {
        return mapper.readTree(mvc.perform(post("/oauth2/token").header("Host", "id.agilitydoghub.com").contentType("application/x-www-form-urlencoded")
                .param("grant_type", "password").param("client_id", "id-web").param("username", "admin@example.test").param("password", PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("access_token").asText();
    }
    record Flow(String value, Cookie cookie) { }
    Flow start(String client, String scopes) throws Exception {
        var response = mvc.perform(authorize(client, scopes).param("login_hint", "admin@example.test").param("ui_locales", "ca es"))
                .andExpect(status().isFound()).andReturn().getResponse();
        assertThat(response.getHeader("Set-Cookie")).contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Domain=");
        assertThat(response.getRedirectedUrl()).startsWith(ID + "/login?").contains("login_hint=admin@example.test", "ui_locales=ca%20es");
        return new Flow(param(response.getRedirectedUrl(), "flow"), response.getCookie(OidcService.COOKIE));
    }
    record Code(String value, Cookie cookie, String access) { }
    Code complete(Flow flow) throws Exception {
        String access = loginAccess();
        var response = mvc.perform(post("/oauth2/session").header("Host", "id.agilitydoghub.com").header("Origin", ID)
                .header("Authorization", "Bearer " + access).cookie(flow.cookie()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("flow", flow.value()))))
                .andExpect(status().isOk()).andReturn().getResponse();
        String url = mapper.readTree(response.getContentAsString()).get("redirectUrl").asText();
        assertThat(url).contains("state=state%20%26%20equals%3Dpreserved");
        return new Code(param(url, "code"), response.getCookie(OidcService.COOKIE), access);
    }
    MockHttpServletRequestBuilder exchange(String client, String code, String verifier) {
        var request = post("/oauth2/token").header("Host", "id.agilitydoghub.com").contentType("application/x-www-form-urlencoded")
                .param("grant_type", "authorization_code").param("client_id", client).param("code", code).param("redirect_uri", redirect(client));
        if (verifier != null) { request.param("code_verifier", verifier); }
        if (client.equals("learn")) { request.param("client_secret", LEARN_SECRET); }
        return request;
    }
    JsonNode exchange(String client, Code code) throws Exception {
        return mapper.readTree(mvc.perform(exchange(client, code.value(), VERIFIER)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @Test void T_01_13_discoveryPublicClientsPkceFullFlowScopedClaimsAndRefresh() throws Exception {
        mvc.perform(get("/.well-known/openid-configuration").header("Host", "unknown.example.test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.issuer").value(ID)).andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"))
                .andExpect(header().string("Cache-Control", "max-age=60, public"));
        for (String client : List.of("clubs-app", "clubs-admin", "id-web", "ar-app")) {
            var request = authorize(client, "openid"); request.params(new org.springframework.util.LinkedMultiValueMap<>());
            request.queryParam("unused", "value");
            mvc.perform(get("/oauth2/authorize").param("client_id", client).param("response_type", "code")
                    .param("redirect_uri", redirect(client)).param("scope", "openid"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.oauth2Error").value("invalid_request"));
        }
        membership("club-b", Set.of(Role.MEMBER, Role.INSTRUCTOR), Role.MEMBER, "member-b");
        var flow = start("ar-app", "openid profile email memberships offline_access");
        var code = complete(flow);
        var result = exchange("ar-app", code);
        assertThat(result.get("refresh_token").asText()).isNotBlank();
        var access = decoder.decode(result.get("access_token").asText());
        assertThat(access.getClaimAsString("clubId")).isNull();
        var id = com.nimbusds.jwt.SignedJWT.parse(result.get("id_token").asText());
        assertThat(id.verify(new com.nimbusds.jose.crypto.RSASSAVerifier((com.nimbusds.jose.jwk.RSAKey) signing.publicKeys().getKeyByKeyId(id.getHeader().getKeyID())))).isTrue();
        assertThat(id.getJWTClaimsSet().getSubject()).isEqualTo("account-a");
        assertThat(id.getJWTClaimsSet().getAudience()).containsExactly("ar-app");
        assertThat(id.getJWTClaimsSet().getStringClaim("nonce")).isEqualTo("test-nonce");
        assertThat(id.getJWTClaimsSet().getStringClaim("at_hash")).hasSize(22);
        assertThat(id.getJWTClaimsSet().getBooleanClaim("email_verified")).isFalse();
        mvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer " + result.get("access_token").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberships.length()").value(2)).andExpect(jsonPath("$.locale").value("en"));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + result.get("id_token").asText())).andExpect(status().isUnauthorized());
        var refreshed = mapper.readTree(refresh(result.get("refresh_token").asText(), "id.agilitydoghub.com", "ar-app")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(refreshed.get("scope")).isEqualTo(result.get("scope"));
        assertThat(refreshed.get("id_token").asText()).isNotBlank();
        refresh(result.get("refresh_token").asText(), "id.agilitydoghub.com", "ar-app").andExpect(status().isBadRequest());
        refresh(refreshed.get("refresh_token").asText(), "id.agilitydoghub.com", "ar-app").andExpect(status().isBadRequest());
        mvc.perform(exchange("ar-app", code.value(), VERIFIER)).andExpect(status().isBadRequest());
        assertThat(mongo.findAll(OidcState.Flow.class)).noneMatch(f -> f.id().equals(flow.value()));
    }
    @Test void T_01_13_wrongVerifierClientRedirectExpiredAndConcurrentCodesCannotBeUsed() throws Exception {
        var code = complete(start("id-web", "openid"));
        for (String verifier : List.of("b".repeat(64), "short")) { mvc.perform(exchange("id-web", code.value(), verifier)).andExpect(status().isBadRequest()); }
        mvc.perform(exchange("id-web", code.value(), null)).andExpect(status().isBadRequest());
        mvc.perform(exchange("ar-app", code.value(), VERIFIER)).andExpect(status().isBadRequest());
        var wrongRedirect = exchange("id-web", code.value(), VERIFIER); wrongRedirect.param("redirect_uri", "https://evil.example.test/");
        mvc.perform(wrongRedirect).andExpect(status().isBadRequest());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> mvc.perform(exchange("id-web", code.value(), VERIFIER)).andReturn().getResponse().getStatus());
            var two = executor.submit(() -> mvc.perform(exchange("id-web", code.value(), VERIFIER)).andReturn().getResponse().getStatus());
            assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(200, 400);
        }
        var expired = complete(start("id-web", "openid")); clock.advance(Duration.ofSeconds(61));
        mvc.perform(exchange("id-web", expired.value(), VERIFIER)).andExpect(status().isBadRequest());
    }
    @Test void T_01_13_scopesAreLeastPrivilegeAndClubFlowRestoresOnlyItsRegisteredTenant() throws Exception {
        var result = exchange("id-web", complete(start("id-web", "openid")));
        assertThat(result.has("refresh_token")).isFalse();
        mvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer " + result.get("access_token").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$.sub").value("account-a"));
        mvc.perform(get("/oauth2/userinfo").header("Host", HOST).header("Authorization", "Bearer " + result.get("access_token").asText()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_MEMBERSHIP"));
        var clubCode = complete(start("clubs-app", "openid profile offline_access"));
        mvc.perform(exchange("clubs-app", clubCode.value(), VERIFIER).with(request -> { request.removeHeader("Host"); request.addHeader("Host", "b.example.test"); return request; })).andExpect(status().isForbidden());
        var clubToken = exchange("clubs-app", clubCode);
        assertThat(decoder.decode(clubToken.get("access_token").asText()).getClaimAsString("clubId")).isEqualTo("club-a");
        var profileToken = mapper.readTree(mvc.perform(put("/api/v1/me/profile").header("Host", HOST)
                .header("Authorization", "Bearer " + clubToken.get("access_token").asText()).contentType("application/json")
                .content("{\"activeProfile\":\"ADMIN\",\"remember\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(decoder.decode(profileToken.get("access_token").asText()).getClaimAsString("scope").split(" "))
                .containsExactlyInAnyOrder("openid", "profile", "offline_access");
        var refreshed = mapper.readTree(refresh(clubToken.get("refresh_token").asText(), "id.agilitydoghub.com", "clubs-app")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(decoder.decode(refreshed.get("access_token").asText()).getClaimAsString("clubId")).isEqualTo("club-a");
        mvc.perform(post("/oauth2/token").header("Host", "id.agilitydoghub.com").param("grant_type", "refresh_token")
                .param("client_id", "clubs-app").param("refresh_token", refreshed.get("refresh_token").asText()).param("scope", "openid email"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/oauth2/token").header("Host", "id.agilitydoghub.com").param("grant_type", "refresh_token")
                .param("client_id", "clubs-app").param("refresh_token", refreshed.get("refresh_token").asText()).param("scope", "openid offline_access"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value(org.hamcrest.Matchers.matchesPattern("(offline_access openid|openid offline_access)")));
        mvc.perform(get("/oauth2/userinfo").header("Host", "b.example.test").header("Authorization", "Bearer " + clubToken.get("access_token").asText()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
        mvc.perform(get("/oauth2/userinfo").header("Host", HOST).header("Authorization", "Bearer " + login().get("access_token").asText())).andExpect(status().isForbidden());
    }
    @Test void T_01_13_confidentialClientSecretsScopesAndCodeWithoutPkce() throws Exception {
        assertThat(registered.findByClientId("learn").getClientSecret()).startsWith("$2a$").isNotEqualTo(LEARN_SECRET);
        for (String secret : List.of("", "wrong")) {
            var request = post("/oauth2/token").header("Host", "id.agilitydoghub.com").param("grant_type", "password")
                    .param("client_id", "learn").param("username", "admin@example.test").param("password", PASSWORD);
            if (!secret.isEmpty()) { request.param("client_secret", secret); }
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        String basic = Base64.getEncoder().encodeToString(("learn:" + LEARN_SECRET).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // The client id is in Basic auth; the global identity host still determines the request context.
        mvc.perform(post("/oauth2/token").header("Host", "id.agilitydoghub.com").header("Authorization", "Basic " + basic)
                .param("grant_type", "password").param("username", "admin@example.test").param("password", PASSWORD).param("scope", "accounts:write"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("accounts:write"));
        var response = mvc.perform(get("/oauth2/authorize").header("Host", "id.agilitydoghub.com").param("response_type", "code")
                .param("client_id", "learn").param("redirect_uri", redirect("learn")).param("scope", "openid email").param("state", "state & equals=preserved"))
                .andExpect(status().isFound()).andReturn().getResponse();
        var code = complete(new Flow(param(response.getRedirectedUrl(), "flow"), response.getCookie(OidcService.COOKIE)));
        var result = mapper.readTree(mvc.perform(exchange("learn", code.value(), null)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer " + result.get("access_token").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("admin@example.test")).andExpect(jsonPath("$.name").doesNotExist());
    }
    @Test void T_01_13_loginRequiresSameOriginFlowCookieFreshGlobalTokenAndConsumesFlow() throws Exception {
        var flow = start("id-web", "openid");
        String access = loginAccess();
        for (String origin : List.of("https://evil.example.test", "null")) {
            mvc.perform(post("/oauth2/session").header("Origin", origin).header("Authorization", "Bearer " + access).cookie(flow.cookie())
                    .contentType("application/json").content(mapper.writeValueAsString(Map.of("flow", flow.value())))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/oauth2/session").header("Origin", ID).header("Authorization", "Bearer " + access)
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("flow", flow.value())))).andExpect(status().isForbidden());
        mvc.perform(post("/oauth2/session").header("Origin", ID).header("Authorization", "Bearer " + access).cookie(new Cookie(OidcService.COOKIE, "wrong"))
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("flow", flow.value())))).andExpect(status().isBadRequest());
        mvc.perform(post("/oauth2/session").header("Origin", ID).header("Authorization", "Bearer " + login().get("access_token").asText()).cookie(flow.cookie())
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("flow", flow.value())))).andExpect(status().isForbidden());
        mvc.perform(post("/oauth2/session").contentType("application/json").content("{\"flow\":\"unknown\"}")).andExpect(status().isUnauthorized());
        complete(flow);
        mvc.perform(post("/oauth2/session").header("Origin", ID).header("Authorization", "Bearer " + access).cookie(flow.cookie())
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("flow", flow.value())))).andExpect(status().isBadRequest());
        clock.advance(Duration.ofSeconds(2));
        var freshFlow = start("id-web", "openid");
        mvc.perform(post("/oauth2/session").header("Origin", ID).header("Authorization", "Bearer " + access).cookie(freshFlow.cookie())
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("flow", freshFlow.value())))).andExpect(status().isUnauthorized());
        clock.advance(Duration.ofMinutes(6));
        mvc.perform(post("/oauth2/session").header("Origin", ID).header("Authorization", "Bearer " + loginAccess()).cookie(freshFlow.cookie())
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("flow", freshFlow.value())))).andExpect(status().isBadRequest());
    }
    @Test void T_01_13_promptNoneSsoForcedLoginLogoutAndRevocation() throws Exception {
        mvc.perform(authorize("id-web", "openid").param("prompt", "none")).andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("https://id.agilitydoghub.com/oidc/callback?error=login_required*"));
        var code = complete(start("id-web", "openid")); var result = exchange("id-web", code);
        mvc.perform(authorize("ar-app", "openid").param("prompt", "none").cookie(code.cookie())).andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("https://ar.agilitydoghub.com/oidc/callback?code=*"));
        mvc.perform(authorize("id-web", "openid").param("prompt", "login").cookie(code.cookie())).andExpect(status().isFound())
                .andExpect(redirectedUrlPattern(ID + "/login?*"));
        mvc.perform(authorize("id-web", "openid").param("max_age", "0").cookie(code.cookie())).andExpect(status().isFound())
                .andExpect(redirectedUrlPattern(ID + "/login?*"));
        String hint = result.get("id_token").asText();
        for (String badHint : List.of("invalid", result.get("access_token").asText())) {
            mvc.perform(get("/connect/logout").param("id_token_hint", badHint).param("post_logout_redirect_uri", ID + "/").cookie(code.cookie()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/connect/logout").param("id_token_hint", hint).param("post_logout_redirect_uri", "https://evil.example.test/").cookie(code.cookie()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/connect/logout").param("id_token_hint", hint).param("post_logout_redirect_uri", ID + "/").param("state", "done").cookie(code.cookie()))
                .andExpect(status().isFound()).andExpect(redirectedUrl(ID + "/?state=done"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        mvc.perform(authorize("id-web", "openid").param("prompt", "none").cookie(code.cookie())).andExpect(status().isFound())
                .andExpect(redirectedUrlPattern(ID + "/oidc/callback?error=login_required*"));
        var revoked = complete(start("id-web", "openid"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("account-a")), new Update().inc("security.tokenFamilyVersion", 1), Account.class);
        mvc.perform(authorize("id-web", "openid").param("prompt", "none").cookie(revoked.cookie())).andExpect(status().isFound())
                .andExpect(redirectedUrlPattern(ID + "/oidc/callback?error=login_required*"));
        mvc.perform(exchange("id-web", revoked.value(), VERIFIER)).andExpect(status().isBadRequest());
    }
    @Test void T_01_13_malformedAuthorizationCannotRedirectToUntrustedClients() throws Exception {
        for (Map.Entry<String, String> invalid : List.of(
                Map.entry("client_id", "unknown"), Map.entry("response_type", "token"),
                Map.entry("redirect_uri", "https://evil.example.test/"), Map.entry("scope", "openid accounts:write"),
                Map.entry("scope", "profile"), Map.entry("code_challenge", "short"),
                Map.entry("code_challenge_method", "plain"), Map.entry("prompt", "none login"),
                Map.entry("prompt", "unknown"), Map.entry("max_age", "-1"), Map.entry("max_age", "invalid"))) {
            var request = authorize("id-web", "openid").with(value -> {
                value.setParameter(invalid.getKey(), invalid.getValue()); return value;
            });
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Location"));
        }
        mvc.perform(authorize("id-web", "openid").param("nonce", "duplicate")).andExpect(status().isBadRequest());
        mvc.perform(authorize("id-web", "openid").param("login_hint", "a".repeat(2049))).andExpect(status().isBadRequest());
        mvc.perform(authorize("id-web", "openid").with(request -> { request.removeParameter("scope"); return request; })).andExpect(status().isBadRequest());
        mvc.perform(authorize("id-web", "openid").with(request -> { request.setParameter("client_id", " "); return request; })).andExpect(status().isBadRequest());
        mvc.perform(authorize("learn", "openid").with(request -> { request.removeParameter("code_challenge"); return request; })).andExpect(status().isBadRequest());
    }
    @Test void T_01_13_rotationIsEncryptedPersistentAndKeepsOldTokensValid() throws Exception {
        String old = loginAccess(); String oldKid = com.nimbusds.jwt.SignedJWT.parse(old).getHeader().getKeyID();
        var stored = keyRepository.findById("active").orElseThrow();
        assertThat(stored.encryptedKeys().contains(signing.current().getPrivateExponent().toString()))
                .as("Encrypted storage must not contain the plaintext private exponent").isFalse();
        // Other tests reset the injected clock; allow any prior rotation to age out.
        clock.advance(Duration.ofMinutes(16)); old = loginAccess(); oldKid = com.nimbusds.jwt.SignedJWT.parse(old).getHeader().getKeyID();
        new RotateKeysCommand(signing).run(new org.springframework.boot.DefaultApplicationArguments("--core.command=identity:rotate-keys"));
        assertThat(signing.current().getKeyID()).isNotEqualTo(oldKid);
        assertThat(decoder.decode(old).getSubject()).isEqualTo("account-a");
        var restarted = new SigningKeys(keyRepository, clock, environment, MASTER);
        assertThat(restarted.current().getKeyID()).isEqualTo(signing.current().getKeyID());
        assertThatThrownBy(signing::rotate).isInstanceOf(ApiException.class);
        mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk()).andExpect(jsonPath("$.keys.length()").value(2))
                .andExpect(header().string("Cache-Control", "max-age=60, public"));
        clock.advance(Duration.ofMinutes(16)); signing.rotate();
        String retiredKid = oldKid;
        assertThat(signing.publicKeys().getKeys()).noneMatch(key -> key.getKeyID().equals(retiredKid));
    }
}
