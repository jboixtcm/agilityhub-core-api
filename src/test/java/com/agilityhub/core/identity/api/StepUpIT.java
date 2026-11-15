package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.HandoffService;
import com.agilityhub.core.identity.domain.Role;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AUTH-01 (S01 R-01-18, owner decision of 13 Nov 2026): exports, remittance files, a member's bank account and the club's payment
 * providers need a staff caller's sign-in of the last 15 minutes. A refresh or an app switch keeps the sign-in time; signing in
 * again (here with the password) opens them again. A caller without the route's role keeps its 403 FORBIDDEN, a member is never
 * asked, and the other routes do not change. Fictional accounts, hosts and ids only.
 */
class StepUpIT extends IdentityIntegrationSupport {
    MockHttpServletResponse signIn(String client) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Host", HOST).secure(true).contentType("application/x-www-form-urlencoded")
                .param("grant_type", "password").param("client_id", client).param("username", "admin@example.test").param("password", PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse();
    }
    MockHttpServletResponse renew(String client, MockHttpServletResponse session) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Host", HOST).header("Origin", "https://" + HOST).secure(true)
                .contentType("application/x-www-form-urlencoded").param("grant_type", "refresh_token").param("client_id", client)
                .cookie(new Cookie(RefreshCookies.NAME, session.getCookie(RefreshCookies.NAME).getValue())))
                .andExpect(status().isOk()).andReturn().getResponse();
    }
    String bearer(MockHttpServletResponse session) throws Exception {
        return "Bearer " + mapper.readTree(session.getContentAsString()).path("access_token").asText();
    }
    ResultActions call(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return mvc.perform(request.header("Host", HOST).header("Authorization", bearer));
    }
    static ResultActions refused(ResultActions result) throws Exception {
        return result.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RECENT_LOGIN_REQUIRED"))
                .andExpect(jsonPath("$.details.maxAgeMinutes").value(15));
    }
    static String code(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return body.isEmpty() || !body.startsWith("{") ? "" : new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("code").asText();
    }
    static final String EXPORT = "/api/v1/members/export?format=xlsx";
    /** The club's own back-office host, the handoff's target URL (R-01-13), as `ConsoleProductHostIT` registers it. */
    static final String ADMIN_HOST = "admin.example.test";

    @org.junit.jupiter.api.BeforeEach void backOfficeHost() {
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), com.agilityhub.core.identity.persistence.HandoffCode.class);
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is("club-a")),
                new org.springframework.data.mongodb.core.query.Update().push("domains", new org.bson.Document("host", ADMIN_HOST)
                        .append("app", "clubs-admin").append("status", "VERIFIED").append("primary", true)), com.agilityhub.core.platform.persistence.Club.class);
        hosts.invalidate(); configs.invalidate("club-a");
    }
    MockHttpServletResponse switchToBackOffice(MockHttpServletResponse app) throws Exception {
        JsonNode created = mapper.readTree(mvc.perform(post("/api/v1/auth/handoff").header("Host", HOST).header("Authorization", bearer(app))
                .contentType("application/json").content("{\"targetClientId\":\"clubs-admin\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return mvc.perform(post("/oauth2/token").header("Host", ADMIN_HOST).secure(true).contentType("application/x-www-form-urlencoded")
                .param("grant_type", HandoffService.GRANT).param("client_id", "clubs-admin").param("token", created.path("code").asText()))
                .andExpect(status().isOk()).andReturn().getResponse();
    }

    @Test void AUTH_01_anExportNeedsASignInOfTheLastFifteenMinutesAndARefreshDoesNotRenewIt() throws Exception {
        var session = signIn("clubs-admin");
        call(get(EXPORT), bearer(session)).andExpect(status().isOk());

        clock.advance(Duration.ofMinutes(16));
        var renewed = renew("clubs-admin", session);
        refused(call(get(EXPORT), bearer(renewed)));
        refused(call(post("/api/v1/members/x-member/data-export").contentType("application/json").content("{\"deliverToMember\":false}"), bearer(renewed)));
        refused(call(get("/api/v1/exports"), bearer(renewed)));
        refused(call(get("/api/v1/exports/x-job"), bearer(renewed)));
        refused(call(get("/api/v1/remittances/x-remittance/file"), bearer(renewed)));
        refused(call(get("/api/v1/remittances/x-remittance/spreadsheet"), bearer(renewed)));
        // The session itself still works for everything else.
        call(get("/api/v1/members"), bearer(renewed)).andExpect(status().isOk());

        var again = signIn("clubs-admin");
        call(get(EXPORT), bearer(again)).andExpect(status().isOk());
        call(get("/api/v1/exports/x-job"), bearer(again)).andExpect(status().isNotFound());
        call(get("/api/v1/remittances/x-remittance/file"), bearer(again)).andExpect(status().isNotFound());
    }

    @Test void AUTH_01_aMembersBankAccountNeedsARecentSignIn() throws Exception {
        var session = signIn("clubs-admin");
        String paymentMethod = mapper.writeValueAsString(Map.of("type", "SEPA_DD", "sepa", Map.of("iban", "ES9121000418450200051332")));
        assertThat(code(call(patch("/api/v1/members/x-member/payment-method").contentType("application/json").content(paymentMethod), bearer(session))))
                .isNotEqualTo("RECENT_LOGIN_REQUIRED");

        clock.advance(Duration.ofMinutes(16));
        var renewed = renew("clubs-admin", session);
        refused(call(patch("/api/v1/members/x-member/payment-method").contentType("application/json").content(paymentMethod), bearer(renewed)));
        // PATCH /members/{id} asks for it only when it carries a (pending member's) payment method.
        refused(call(patch("/api/v1/members/x-member").contentType("application/json")
                .content("{\"version\":0,\"paymentMethod\":{\"type\":\"SEPA_DD\",\"sepa\":{\"iban\":\"ES9121000418450200051332\"}}}"), bearer(renewed)));
        assertThat(code(call(patch("/api/v1/members/x-member").contentType("application/json").content("{\"version\":0,\"remarks\":\"Fictional\"}"), bearer(renewed))))
                .isNotEqualTo("RECENT_LOGIN_REQUIRED");
    }

    /** An app switch keeps the sign-in time of the session it came from: a recent one stays recent, an old one opens nothing. */
    @Test void AUTH_01_aHandoffKeepsTheSignInTimeOfItsSourceSession() throws Exception {
        var fresh = switchToBackOffice(signIn("clubs-app"));
        mvc.perform(get(EXPORT).header("Host", ADMIN_HOST).header("Authorization", bearer(fresh))).andExpect(status().isOk());

        var app = signIn("clubs-app");
        clock.advance(Duration.ofMinutes(16));
        var admin = switchToBackOffice(renew("clubs-app", app));
        refused(mvc.perform(get(EXPORT).header("Host", ADMIN_HOST).header("Authorization", bearer(admin))));
    }

    /** The role check comes first: an instructor keeps its 403 FORBIDDEN on an ADMIN export, a member its own data. */
    @Test void AUTH_01_aCallerWithoutTheRoleKeepsItsAnswerAndAMemberIsNeverAsked() throws Exception {
        membership("club-a", Set.of(Role.INSTRUCTOR, Role.MEMBER), Role.INSTRUCTOR, "member-a");
        var instructor = signIn("clubs-app");
        clock.advance(Duration.ofMinutes(16));
        var renewed = renew("clubs-app", instructor);
        call(get(EXPORT), bearer(renewed)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        refused(call(get("/api/v1/instructor/week/export?format=pdf"), bearer(renewed)));

        membership("club-a", Set.of(Role.MEMBER), Role.MEMBER, "member-a");
        var member = signIn("clubs-app");
        clock.advance(Duration.ofMinutes(16));
        call(get("/api/v1/exports/x-job"), bearer(renew("clubs-app", member))).andExpect(status().isNotFound());
    }

    /** The console's payment providers: an AgilityHub administrator's sign-in of the last 15 minutes. */
    @Test void AUTH_01_aPaymentProviderChangeNeedsARecentSignIn() throws Exception {
        for (long minutes : new long[]{16, 1}) {
            var request = put("/api/v1/platform/clubs/club-a/payment-providers/STRIPE").contentType("application/json").content("{\"version\":0}")
                    .with(jwt().jwt(token -> token.subject("account-a").claim("platformRoles", java.util.List.of("AGILITYHUB_ADMIN"))
                            .claim("auth_time", clock.instant().minus(Duration.ofMinutes(minutes)).getEpochSecond()))
                            .authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN")));
            var deletion = delete("/api/v1/platform/clubs/club-a/payment-providers/STRIPE").param("version", "0")
                    .with(jwt().jwt(token -> token.subject("account-a").claim("platformRoles", java.util.List.of("AGILITYHUB_ADMIN"))
                            .claim("auth_time", clock.instant().minus(Duration.ofMinutes(minutes)).getEpochSecond()))
                            .authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN")));
            if (minutes > 15) {
                refused(mvc.perform(request));
                refused(mvc.perform(deletion));
            } else {
                assertThat(code(mvc.perform(request))).isNotEqualTo("RECENT_LOGIN_REQUIRED");
                assertThat(code(mvc.perform(deletion))).isNotEqualTo("RECENT_LOGIN_REQUIRED");
            }
        }
    }
}
