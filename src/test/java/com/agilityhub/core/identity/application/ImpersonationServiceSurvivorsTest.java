package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.HandoffCodeRepository;
import com.agilityhub.core.identity.persistence.ImpersonationGrant;
import com.agilityhub.core.identity.persistence.ImpersonationGrantRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.platform.application.ClubAppUrls;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.MemberIdentityAccess;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link ImpersonationService} (S01 R-01-09, T-01-11): a refused impersonation records
 * IMPERSONATION_DENIED; a club without a verified club-app domain gets a grant without launch URL; `revoke` claims (true) every
 * JWT-shaped credential, also one that does not decode and an ordinary access token, so the caller never treats it as a refresh
 * token (OAuthController.java:161); the issued grant prints redacted. Collaborators are mocks; the JWTs carry the claims the
 * service issues (TokenService.java:216-225 for an access token, ImpersonationService.java:68-72 for an impersonation token) in a
 * compact form built at run time with a fictional signature; fictional data.
 */
class ImpersonationServiceSurvivorsTest {
    static final String CLUB = "club-a";
    static final String ISSUER = "https://id.example.test";
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();

    final ImpersonationGrantRepository grants = mock(ImpersonationGrantRepository.class);
    final IdentityService identities = mock(IdentityService.class);
    final MemberIdentityAccess members = mock(MemberIdentityAccess.class);
    final AuthSettings settings = mock(AuthSettings.class);
    final EventPublisher events = mock(EventPublisher.class);
    final SecurityEvents security = mock(SecurityEvents.class);
    final JwtEncoder encoder = mock(JwtEncoder.class);
    final JwtDecoder decoder = mock(JwtDecoder.class);
    final HandoffCodeRepository codes = mock(HandoffCodeRepository.class);
    final ClubAppUrls urls = mock(ClubAppUrls.class);
    final ImpersonationService service = new ImpersonationService(grants, identities, members, settings, events, security, encoder, decoder,
            Clock.fixed(NOW, ZoneOffset.UTC), ISSUER, codes, urls);

    @BeforeEach void setUp() { TenantContext.open(CLUB); }
    @AfterEach void tearDown() { TenantContext.clear(); }

    static Account account(String id, String email, String name) {
        return new Account(id, email, name, "ca", null, Set.of(), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(),
                false, NOW.minusSeconds(86_400));
    }

    /** A JWT as the RS256 encoder returns it and the decoder reads it back: the claims, in a compact `header.payload.signature`. */
    static Jwt jwt(Map<String, Object> claims) {
        var payload = new LinkedHashMap<String, Object>();
        claims.forEach((name, value) -> payload.put(name, value instanceof Instant instant ? instant.getEpochSecond() : value));
        var base64 = Base64.getUrlEncoder().withoutPadding();
        String value;
        try {
            value = base64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                    + base64.encodeToString(JSON.writeValueAsBytes(payload)) + "."
                    + base64.encodeToString("fictional-signature".getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
        return Jwt.withTokenValue(value).header("alg", "RS256").claims(all -> all.putAll(claims)).build();
    }

    /** Marc's clubs-app access token (TokenService.java:216-225: member membership, MEMBER profile, 15 min). */
    static Jwt accessToken() {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", ISSUER); claims.put("sub", "acc-member"); claims.put("aud", List.of("clubs-app"));
        claims.put("iat", NOW); claims.put("exp", NOW.plus(TokenService.ACCESS_TTL));
        claims.put("jti", "8d4c6f0e-1a2b-4c3d-9e8f-0a1b2c3d4e5f"); claims.put("sid", "family-1"); claims.put("azp", "clubs-app");
        claims.put("email", "marc@example.test"); claims.put("name", "Marc Example"); claims.put("locale", "ca");
        claims.put("platformRoles", List.of()); claims.put("scope", "email memberships offline_access openid profile");
        claims.put("auth_time", NOW.minusSeconds(3600).getEpochSecond()); claims.put("clubId", CLUB); claims.put("roles", List.of("MEMBER"));
        claims.put("activeProfile", "MEMBER"); claims.put("memberId", "member-1");
        return jwt(claims);
    }

    /** Laura's impersonation token for Marc's member (ImpersonationService.java:68-72, grant of 60 min). */
    static Jwt impersonationToken() {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", ISSUER); claims.put("sub", "acc-member"); claims.put("aud", List.of("clubs-app"));
        claims.put("iat", NOW); claims.put("exp", NOW.plusSeconds(3600)); claims.put("jti", "grant-1"); claims.put("azp", "clubs-app");
        claims.put("clubId", CLUB); claims.put("roles", List.of("MEMBER")); claims.put("activeProfile", "MEMBER");
        claims.put("memberId", "member-1"); claims.put("imp", true); claims.put("actorAccountId", "acc-admin");
        claims.put("impersonatedMemberId", "member-1"); claims.put("name", "Marc Example"); claims.put("email", "marc@example.test");
        claims.put("locale", "ca");
        return jwt(claims);
    }

    /** CurrentUserFilter.java:34 denies an impersonation token's actor that asks for another impersonation. */
    @Test void T_01_11_aRefusedImpersonationRecordsTheDenial() {
        assertThatThrownBy(() -> service.deny("acc-admin")).isInstanceOf(ApiException.class).hasMessage("IMPERSONATION_DENIED");

        verify(security).record(SecurityEvents.Type.IMPERSONATION_DENIED, "acc-admin", CLUB);
    }

    /**
     * The actor is a club-as-code admin (MembershipService.java:58-63: no member), the target a census member
     * (SignupIdentityService.java:39); the club has no verified club-app domain, so ClubAppUrls.java:23-25 throws UNKNOWN_HOST.
     */
    @Test void T_01_11_aClubWithoutAVerifiedClubAppDomainGetsAGrantWithoutLaunchUrl() {
        var admin = account("acc-admin", "laura@example.test", "Laura Example");
        var target = account("acc-member", "marc@example.test", "Marc Example");
        when(identities.current("acc-admin")).thenReturn(new IdentityService.Session(admin, new Membership("membership-1", "acc-admin", CLUB,
                null, Set.of(Role.ADMIN), Membership.Status.ACTIVE, null, false, null, NOW.minusSeconds(86_400), null)));
        when(members.impersonationAccount("member-1")).thenReturn("acc-member");
        when(identities.current("acc-member")).thenReturn(new IdentityService.Session(target, new Membership("membership-2", "acc-member",
                CLUB, "member-1", Set.of(Role.MEMBER), Membership.Status.ACTIVE, Role.MEMBER, false, null, NOW.minusSeconds(86_400), null)));
        when(settings.integer("auth.impersonationMinutes")).thenReturn(60);
        when(grants.insert(any(ImpersonationGrant.class))).thenAnswer(call -> call.getArgument(0));
        when(encoder.encode(any(JwtEncoderParameters.class)))
                .thenAnswer(call -> jwt(call.<JwtEncoderParameters>getArgument(0).getClaims().getClaims()));
        when(urls.login("clubs-app")).thenThrow(new ApiException(ErrorCode.UNKNOWN_HOST));

        var issued = service.create("acc-admin", "member-1", "Booking on behalf of the member");

        assertThat(issued.launchUrl()).isNull();
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(issued.token().getClaimAsBoolean("imp")).isTrue();
        verifyNoInteractions(codes);
    }

    /** NimbusJwtDecoder answers a token whose signature does not verify with BadJwtException (a JwtException). */
    @Test void T_01_11_aJwtShapedCredentialThatDoesNotDecodeIsClaimedByTheImpersonationRevoke() {
        String value = impersonationToken().getTokenValue();
        when(decoder.decode(value)).thenThrow(new BadJwtException("An error occurred while attempting to decode the Jwt: Signed JWT rejected: Invalid signature"));

        assertThat(service.revoke("acc-member", value)).isTrue();
        verifyNoInteractions(grants, events);
    }

    @Test void T_01_11_anOrdinaryAccessTokenIsClaimedByTheImpersonationRevokeWithoutEndingAnyGrant() {
        var token = accessToken();
        when(decoder.decode(token.getTokenValue())).thenReturn(token);

        assertThat(service.revoke("acc-member", token.getTokenValue())).isTrue();
        verifyNoInteractions(grants, events);
    }

    @Test void T_01_11_anIssuedGrantPrintsRedacted() {
        var issued = new ImpersonationService.Issued("grant-1", NOW.plusSeconds(3600), impersonationToken(),
                "https://club-a.example.test/entrar?handoff=fictional-launch-code-of-43-base64url-chars");

        assertThat(issued.toString()).isEqualTo("Issued[redacted]");
    }
}
