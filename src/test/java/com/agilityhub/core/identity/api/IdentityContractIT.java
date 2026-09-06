package com.agilityhub.core.identity.api;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IdentityContractIT extends IdentityIntegrationSupport {
    @org.springframework.beans.factory.annotation.Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    @org.springframework.beans.factory.annotation.Value("${identity.issuer}") String issuer;
    record Endpoint(String method, String path, String body, String authority, boolean clubRequired) { }
    static Stream<Endpoint> accountEndpoints() {
        return Stream.of(
                new Endpoint("GET", "/oauth2/userinfo", null, "SCOPE_openid", false),
                new Endpoint("POST", "/api/v1/auth/handoff", "{\"targetClientId\":\"clubs-admin\"}", "ROLE_ADMIN", true),
                new Endpoint("GET", "/api/v1/me/onboarding", null, "ROLE_MEMBER", false),
                new Endpoint("PUT", "/api/v1/me/onboarding", "{\"consentAccepted\":true,\"consentVersion\":\"v1\"}", "ROLE_MEMBER", false),
                new Endpoint("POST", "/api/v1/me/onboarding/postpone", null, "ROLE_MEMBER", false),
                new Endpoint("POST", "/api/v1/members/member-a/impersonation-token", "{}", "ROLE_ADMIN", true),
                new Endpoint("POST", "/api/v1/platform/accounts", "{\"email\":\"new@example.test\",\"name\":\"Example\",\"locale\":\"en\"}", "ROLE_AGILITYHUB_ADMIN", false),
                new Endpoint("PUT", "/api/v1/accounts/account-a/password", "{\"passwordHash\":\"fictional-hash\"}", "ROLE_AGILITYHUB_ADMIN", false));
    }
    MockHttpServletRequestBuilder request(Endpoint endpoint) {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(HttpMethod.valueOf(endpoint.method()), endpoint.path());
        if (endpoint.body() != null) { request.contentType("application/json").content(endpoint.body()); }
        return request;
    }

    @ParameterizedTest @MethodSource("accountEndpoints")
    void T_01_17_protectedContractsRequireAuthenticationAndEnforceTenantBoundaries(Endpoint endpoint) throws Exception {
        mvc.perform(request(endpoint)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(request(endpoint).header("Host", HOST)
                        .with(jwt().jwt(j -> j.subject("account-a").claim("clubId", "club-a"))
                                .authorities(new SimpleGrantedAuthority(endpoint.authority()))))
                .andExpect(status().isNotImplemented()).andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.length()").value(4)).andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isMap()).andExpect(jsonPath("$.traceId").isNotEmpty());
        boolean global = endpoint.path().startsWith("/api/v1/platform/") || endpoint.path().startsWith("/api/v1/accounts/");
        mvc.perform(request(endpoint).header("Host", "b.example.test")
                        .with(jwt().jwt(j -> j.subject("account-a").claim("clubId", "club-a"))
                                .authorities(new SimpleGrantedAuthority(endpoint.authority()))))
                .andExpect(status().is(global ? 501 : 403)).andExpect(jsonPath("$.code").value(global ? "NOT_IMPLEMENTED" : "TENANT_MISMATCH"));
        mvc.perform(request(endpoint).header("Host", "id.example.test")
                        .with(jwt().jwt(j -> j.subject("account-a"))
                                .authorities(new SimpleGrantedAuthority(endpoint.authority()))))
                .andExpect(status().is(endpoint.clubRequired() ? 403 : 501));
        assertThat(accounts.findById("account-a").orElseThrow().name()).isEqualTo("Example Admin");
        assertThat(accounts.findByEmail("new@example.test")).isEmpty();
    }

    @Test void T_01_08_magicLinkContractIsPublicAndBindsTheSpecifiedSnakeCaseFields() throws Exception {
        for (String host : List.of(HOST, "id.example.test")) {
            mvc.perform(post("/api/v1/auth/magic-link").header("Host", host).contentType("application/json")
                            .content("{\"email\":\"member@example.test\",\"purpose\":\"LOGIN\",\"client_id\":\"clubs-app\",\"redirect_uri\":\"https://app.example.test/activacio\"}"))
                    .andExpect(status().isAccepted());
        }
        mvc.perform(post("/api/v1/auth/magic-link").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest @ValueSource(strings = {"urn:agilityhub:grant:handoff", "authorization_code"})
    void T_01_24_pendingGrantsReachThe501ContractWithoutChangingExistingGrants(String grant) throws Exception {
        for (String host : List.of(HOST, "id.example.test")) {
            mvc.perform(post("/oauth2/token").header("Host", host).contentType("application/x-www-form-urlencoded")
                            .param("grant_type", grant).param("client_id", "id-web").param("token", "fictional")
                            .param("code", "fictional").param("code_verifier", "fictional").param("redirect_uri", "https://id.example.test/callback"))
                    .andExpect(status().isNotImplemented()).andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
        }
    }

    @Test void T_01_26_onboardingUsesNestedFieldsAndRequiresAcceptedVersionedConsent() throws Exception {
        String body = """
                {"consentAccepted":true,"consentVersion":"v1","fields":{"name":"Example","locale":"es","phone":"+34900000000"},"imageConsent":false}
                """;
        var request = mapper.readValue(body, IdentityRequests.OnboardingRequest.class);
        assertThat(request.fields()).isEqualTo(new IdentityRequests.OnboardingFields("Example", "es", "+34900000000"));
        assertThat(request.imageConsent()).isFalse();
        for (String role : List.of("MEMBER", "INSTRUCTOR", "ADMIN")) {
            for (var endpoint : accountEndpoints().filter(e -> e.path().startsWith("/api/v1/me/onboarding")).toList()) {
                var call = request(endpoint);
                if (endpoint.method().equals("PUT")) { call.content(body); }
                mvc.perform(call.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                        .andExpect(status().isNotImplemented()).andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
            }
        }
        for (String invalid : List.of("{}", "{\"consentAccepted\":false,\"consentVersion\":\"v1\"}",
                "{\"consentAccepted\":true,\"consentVersion\":\" \"}",
                "{\"privacyAccepted\":true,\"privacyPolicyVersion\":\"v1\"}")) {
            mvc.perform(put("/api/v1/me/onboarding").with(jwt()).contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test void T_01_26_onboardingSerializationRetainsExplicitNullConsentAndFieldValues() throws Exception {
        var state = new IdentityResponses.OnboardingState(false, 0, null,
                List.of(new IdentityResponses.OnboardingField("phone", null, false)));
        assertThat(mapper.readTree(mapper.writeValueAsString(state))).isEqualTo(mapper.readTree("""
                {"pending":false,"postponeRemaining":0,"requiredConsent":null,"fields":[{"key":"phone","value":null,"required":false}]}
                """));
        for (var policy : IdentityResponses.ConsentPolicy.values()) {
            var consent = new IdentityResponses.RequiredConsent(policy, "v1", "https://example.test/privacy");
            var pending = new IdentityResponses.OnboardingState(true, 3, consent,
                    List.of(new IdentityResponses.OnboardingField("name", "Example", true)));
            var json = mapper.readTree(mapper.writeValueAsString(pending));
            assertThat(json.at("/requiredConsent/policy").asText()).isEqualTo(policy.name());
            assertThat(json.at("/requiredConsent/version").asText()).isEqualTo("v1");
            assertThat(json.at("/requiredConsent/url").asText()).isEqualTo("https://example.test/privacy");
            assertThat(mapper.treeToValue(json, IdentityResponses.OnboardingState.class)).isEqualTo(pending);
        }
    }

    @Test void T_01_13_oidcContractsAreGlobalAndUserInfoRequiresOpenIdScope() throws Exception {
        for (var request : List.of(get("/.well-known/openid-configuration"),
                get("/connect/logout").param("id_token_hint", "fictional").param("post_logout_redirect_uri", "https://id.example.test"),
                get("/oauth2/authorize").param("response_type", "code").param("client_id", "id-web")
                        .param("redirect_uri", "https://id.example.test/callback").param("scope", "openid")
                        .param("state", "fictional").param("code_challenge", "fictional").param("code_challenge_method", "S256"))) {
            mvc.perform(request.header("Host", "id.example.test"))
                    .andExpect(status().isNotImplemented()).andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
        }
        mvc.perform(get("/oauth2/authorize")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/oauth2/userinfo").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/me").with(jwt().jwt(j -> j.subject("account-a")))).andExpect(status().isOk());
    }

    @Test void T_01_11_impersonationAndPasswordContractsRejectImpersonatedAccounts() throws Exception {
        for (String role : List.of("MEMBER", "INSTRUCTOR", "ADMIN")) {
            mvc.perform(post("/api/v1/members/member-a/impersonation-token").contentType("application/json").content("{}")
                            .with(jwt().jwt(j -> j.claim("clubId", "club-a").claim("imp", true))
                                    .authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IMPERSONATION_DENIED"));
        }
        mvc.perform(post("/api/v1/members/member-a/impersonation-token").contentType("application/json").content("{}")
                        .with(jwt().jwt(j -> j.claim("clubId", "club-a")).authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IMPERSONATION_DENIED"));
        mvc.perform(put("/api/v1/me/password").contentType("application/json").content("{\"new\":\"fictional\",\"repeat\":\"fictional\"}")
                        .with(jwt().jwt(j -> j.claim("clubId", "club-a").claim("imp", true))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/me").with(jwt().jwt(j -> j.claim("clubId", "club-a").claim("imp", true))))
                .andExpect(status().isNotImplemented());
    }

    @Test void T_01_14_accountWriteContractsRequireLearnAudienceAndScopeOrPlatformAdmin() throws Exception {
        for (var endpoint : accountEndpoints().filter(e -> e.authority().equals("ROLE_AGILITYHUB_ADMIN")).toList()) {
            for (String audience : List.of("learn", "clubs-app")) {
                for (String authority : List.of("SCOPE_accounts:write", "ROLE_ADMIN", "ROLE_MEMBER")) {
                    mvc.perform(request(endpoint).with(jwt().jwt(j -> j.audience(List.of(audience)))
                                    .authorities(new SimpleGrantedAuthority(authority))))
                            .andExpect(status().is(audience.equals("learn") && authority.equals("SCOPE_accounts:write") ? 501 : 403));
                }
            }
            mvc.perform(request(endpoint).with(jwt().jwt(j -> j.claim("imp", true))
                            .authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN"))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test void T_01_09_passwordBodyRequiresNewAndRepeatAndProfileRequiresRemember() throws Exception {
        for (var request : List.of(put("/api/v1/me/password").content("{\"newPassword\":\"fictional\",\"repeat\":\"fictional\"}"),
                put("/api/v1/me/profile").content("{\"activeProfile\":\"MEMBER\"}"))) {
            mvc.perform(request.contentType("application/json").with(jwt().jwt(j -> j.claim("clubId", "club-a"))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test void T_01_17_signedPlatformRoleAndScopeClaimsReachTheirAuthorizedContracts() throws Exception {
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer(issuer).subject("account-a")
                .audience(List.of("clubs-app")).issuedAt(clock.instant()).expiresAt(clock.instant().plusSeconds(900))
                .claim("platformRoles", List.of("AGILITYHUB_ADMIN")).claim("scope", "openid").build();
        String token = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(claims)).getTokenValue();
        mvc.perform(post("/api/v1/platform/accounts").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"email\":\"new@example.test\",\"name\":\"Example\",\"locale\":\"en\"}"))
                .andExpect(status().isNotImplemented());
        mvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotImplemented());
    }
}
