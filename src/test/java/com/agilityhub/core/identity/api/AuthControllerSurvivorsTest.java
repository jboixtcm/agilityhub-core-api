package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.HandoffService;
import com.agilityhub.core.identity.application.MagicLinkService;
import com.agilityhub.core.identity.persistence.MagicLinkToken;
import com.agilityhub.core.shared.application.RateLimits;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import static com.agilityhub.core.identity.api.IdentityRequests.HandoffRequest;
import static com.agilityhub.core.identity.api.IdentityRequests.MagicLinkPurpose;
import static com.agilityhub.core.identity.api.IdentityRequests.MagicLinkRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link AuthController} (S01 R-01-04, R-01-09, R-01-13): the `X-Club-Host` override of the
 * magic-link host exists only under the local profile, and a refused impersonation handoff is recorded as a security event.
 */
class AuthControllerSurvivorsTest {
    static final String CLUB = "club-a";

    final MagicLinkService magicLinks = mock(MagicLinkService.class);
    final SecurityEvents events = mock(SecurityEvents.class);
    final HandoffService handoffs = mock(HandoffService.class);
    // Disabled limits answer 0 (RateLimits:67): the request is never throttled here. A fixed clock all the same: no wall time.
    final RateLimits limits = new RateLimits(false, Map.of(), Clock.fixed(java.time.Instant.parse("2026-10-05T08:00:00Z"), java.time.ZoneOffset.UTC));

    @BeforeEach void reset() { TenantContext.clear(); }
    @AfterEach void clear() { TenantContext.clear(); }

    AuthController controller(boolean local) {
        var environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(local);
        return new AuthController(magicLinks, limits, events, environment, handoffs);
    }

    static MockHttpServletRequest magicLinkRequest(boolean withClubHostHeader) {
        var http = new MockHttpServletRequest("POST", "/api/v1/auth/magic-link");
        http.addHeader("Host", "app.club-a.example.test");
        if (withClubHostHeader) { http.addHeader("X-Club-Host", "other.example.test"); }
        http.setRemoteAddr("192.0.2.10");
        return http;
    }

    static final MagicLinkRequest REQUEST = new MagicLinkRequest("laura@example.test", MagicLinkPurpose.LOGIN, "clubs-app", null);

    @Test void T_01_08_outsideTheLocalProfileTheLinkHostIsTheRequestHost() {
        controller(false).magicLink(REQUEST, magicLinkRequest(true));

        verify(magicLinks).request(eq("laura@example.test"), eq(MagicLinkToken.Purpose.LOGIN), eq("clubs-app"), isNull(),
                eq("app.club-a.example.test"), eq("192.0.2.10"), isNull());
    }

    @Test void T_01_08_underTheLocalProfileTheClubHostHeaderSelectsTheLinkHost() {
        controller(true).magicLink(REQUEST, magicLinkRequest(true));

        verify(magicLinks).request(eq("laura@example.test"), eq(MagicLinkToken.Purpose.LOGIN), eq("clubs-app"), isNull(),
                eq("other.example.test"), eq("192.0.2.10"), isNull());
    }

    @Test void T_01_08_underTheLocalProfileWithoutTheHeaderTheLinkHostIsTheRequestHost() {
        controller(true).magicLink(REQUEST, magicLinkRequest(false));

        verify(magicLinks).request(eq("laura@example.test"), eq(MagicLinkToken.Purpose.LOGIN), eq("clubs-app"), isNull(),
                eq("app.club-a.example.test"), eq("192.0.2.10"), isNull());
    }

    @Test void T_01_11_anImpersonationTokenCannotCreateAHandoffAndTheRefusalIsRecorded() {
        // The impersonation JWT passes CurrentUserFilter (ImpersonationService.validate needs only the club context) and is
        // authenticated as ROLE_MEMBER (SecurityConfiguration:88-89), so it reaches this handler.
        TenantContext.open(CLUB);
        // The grant's claims (ImpersonationService.java:68-72): ROLE_MEMBER only, `memberId` = the impersonated member, no `sid`.
        var jwt = Jwt.withTokenValue("eyJ.imp.sig").header("alg", "RS256").subject("acc-member").claim("azp", "clubs-app")
                .claim("imp", true).claim("actorAccountId", "acc-admin").claim("impersonatedMemberId", "member-1").claim("clubId", CLUB)
                .claim("roles", java.util.List.of("MEMBER")).claim("activeProfile", "MEMBER").claim("memberId", "member-1")
                .claim("name", "Family Example").build();

        assertThatThrownBy(() -> controller(false).handoff(new HandoffRequest("clubs-admin"), jwt))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.IMPERSONATION_DENIED));

        verify(events).record(SecurityEvents.Type.IMPERSONATION_DENIED, "acc-admin", CLUB);
        verifyNoInteractions(handoffs);
    }
}
