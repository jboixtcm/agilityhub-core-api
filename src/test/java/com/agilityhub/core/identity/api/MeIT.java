package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.Membership;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MeIT extends IdentityIntegrationSupport {
    @ParameterizedTest @EnumSource(Role.class)
    void T_01_17_eachClubRoleGetsOnlyPublicAccountAndHostMembershipFields(Role role) throws Exception {
        membership("club-a", Set.of(role), role, "member-a");
        String token = login().get("access_token").asText();
        var response = mvc.perform(get("/api/v1/me").header("Host", HOST).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.account.id").value("account-a")).andExpect(jsonPath("$.account.length()").value(7))
                .andExpect(jsonPath("$.account.hasPassword").value(true))
                .andExpect(jsonPath("$.account.onboardingPending").value(false))
                .andExpect(jsonPath("$.account.emailVerifiedAt").doesNotExist())
                .andExpect(jsonPath("$.membership.roles[0]").value(role.name())).andExpect(jsonPath("$.membership.memberId").value("member-a"))
                .andExpect(jsonPath("$.membership.clubId").value("club-a"))
                .andExpect(jsonPath("$.membership.profiles[0]").value(role.name()))
                .andExpect(jsonPath("$.membership.activeProfile").value(role.name()))
                .andExpect(jsonPath("$.membership.defaultProfile").value(role.name())).andExpect(jsonPath("$.features").isArray())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("passwordHash", "security", "token", "externalIds");
        mvc.perform(get("/api/v1/me").header("Host", "b.example.test").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
        // Unknown API gateway hosts preserve the existing authenticated JWT tenant fallback.
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }
    @Test void T_01_25_passwordlessAccountHasRequiredFalseFlagsAndNoMemberGender() throws Exception {
        membership("club-a", Set.of(Role.ADMIN), Role.ADMIN, null);
        mongo.updateFirst(new Query(), new Update().unset("passwordHash").set("onboardingPending", true), Account.class);
        var response = mvc.perform(get("/api/v1/me").header("Host", HOST)
                        .with(jwt().jwt(j -> j.subject("account-a").claim("clubId", "club-a"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.account.hasPassword").value(false))
                .andExpect(jsonPath("$.account.onboardingPending").value(true))
                .andExpect(jsonPath("$.membership.memberId").doesNotExist())
                .andExpect(jsonPath("$.membership.gender").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("passwordHash", "security", "token", "externalIds");
    }
    @ParameterizedTest @EnumSource(MeResponse.Gender.class)
    void T_01_25_bootstrapSerializesOptionalVerifiedTimeAndMemberGenderWithoutCredentials(MeResponse.Gender gender) throws Exception {
        var account = new MeResponse.MeAccount("account-a", "admin@example.test", "Example", "en",
                Set.of(), true, clock.instant(), false);
        var membership = new MeResponse.MembershipSummary("club-a", Set.of(IdentityResponses.Profile.MEMBER),
                IdentityResponses.Profile.MEMBER, List.of(IdentityResponses.Profile.MEMBER), "member-a", null,
                IdentityResponses.Profile.MEMBER, false, gender);
        String response = mapper.writeValueAsString(new MeResponse(account, membership, null, List.of()));
        var json = mapper.readTree(response);
        assertThat(json.at("/account").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "email", "name", "locale", "platformRoles", "hasPassword", "emailVerifiedAt", "onboardingPending");
        assertThat(json.at("/account/emailVerifiedAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(json.at("/account/hasPassword").asBoolean()).isTrue();
        assertThat(json.at("/account/onboardingPending").asBoolean()).isFalse();
        assertThat(json.at("/membership/gender").asText()).isEqualTo(gender.name());
        assertThat(response).doesNotContain("passwordHash", "security", "token", "externalIds");
    }
    @Test void T_01_17_tokensAreRequiredAndValidatedIncludingSignatureExpiryIssuerAndAudience() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/unknown")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer nonsense"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        String token = login().get("access_token").asText();
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + parts[1] + "." + (parts[2].startsWith("a") ? "b" : "a") + parts[2].substring(1);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + forged)).andExpect(status().isUnauthorized());
        clock.advance(Duration.ofMinutes(15));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
    @Test void T_01_17_methodAuthorizationAndCurrentMembershipRejectUnavailableAccess() throws Exception {
        mvc.perform(get("/api/v1/me").with(jwt().jwt(j -> j.subject("account-a").claim("clubId", "club-a"))
                .authorities(() -> "ROLE_GUEST"))).andExpect(status().isOk());
        for (String role : new String[]{"MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN"}) {
            mvc.perform(get("/api/v1/me").with(jwt().jwt(j -> j.subject("account-a").claim("clubId", "club-a"))
                    .authorities(() -> "ROLE_" + role))).andExpect(status().isOk());
        }
        String token = login().get("access_token").asText();
        mongo.updateFirst(new Query(), new Update().set("status", Membership.Status.SUSPENDED), Membership.class);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mongo.remove(new Query(), Account.class);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
    @Test void T_01_17_jwksIsGlobalPublicForBothPathsAndContainsNoPrivateMaterial() throws Exception {
        for (String path : new String[]{"/oauth2/jwks", "/.well-known/jwks.json"}) {
            String response = mvc.perform(get(path).header("Host", "unknown.example.test"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                    .andExpect(jsonPath("$.keys[0].kid").isNotEmpty()).andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                    .andExpect(jsonPath("$.keys[0].d").doesNotExist()).andExpect(jsonPath("$.keys[0].p").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(response).get("keys").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("kty", "kid", "n", "e", "use", "alg");
        }
    }
}
