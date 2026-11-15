package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.ImpersonationGrant;
import com.agilityhub.core.identity.persistence.ImpersonationGrantRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AUTH-01 (S01 R-01-18, owner decision of 13 Nov 2026): a support token's sign-in is the one of the console session that started it,
 * so the recent-login actions a support token may still reach (a member's bank account) need its platform administrator to have
 * signed in within 15 minutes; a console token without a sign-in time gives a support token without one, which is never recent.
 * Collaborators are mocks; fictional data.
 */
class SupportAccessRecentLoginTest {
    static final Instant NOW = Instant.parse("2026-11-13T18:00:00Z");
    final ImpersonationGrantRepository grants = mock(ImpersonationGrantRepository.class);
    final AccountRepository accounts = mock(AccountRepository.class);
    final AuthSettings settings = mock(AuthSettings.class);
    final JwtEncoder encoder = mock(JwtEncoder.class);
    final SupportAccessService service = new SupportAccessService(grants, accounts, settings, mock(EventPublisher.class), encoder,
            Clock.fixed(NOW, ZoneOffset.UTC), "https://id.example.test");

    @BeforeEach void setUp() {
        TenantContext.clear(); TenantContext.open("club-a");
        when(settings.integer("platform.supportAccessMinutes")).thenReturn(60);
        when(grants.insert(any(ImpersonationGrant.class))).thenAnswer(call -> call.getArgument(0));
        when(accounts.findById("platform-1")).thenReturn(Optional.of(new Account("platform-1", "platform@example.test", "Example Platform", "ca",
                null, Set.of(Account.PlatformRole.AGILITYHUB_ADMIN), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(), false, NOW)));
        when(encoder.encode(any())).thenReturn(Jwt.withTokenValue("eyJ.fictional.sig").header("alg", "RS256").subject("platform-1").build());
    }
    @AfterEach void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    Map<String, Object> issuedClaims(Object consoleSignIn) {
        var console = Jwt.withTokenValue("eyJ.console.sig").header("alg", "RS256").subject("platform-1").claim("platformRoles", List.of("AGILITYHUB_ADMIN"));
        if (consoleSignIn != null) { console.claim("auth_time", consoleSignIn); }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(console.build(), List.of(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN"))));
        service.start("Fictional ticket 42");
        var parameters = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(encoder).encode(parameters.capture());
        return parameters.getValue().getClaims().getClaims();
    }

    @Test void AUTH_01_aSupportTokenCarriesTheSignInTimeOfTheConsoleSessionThatStartedIt() {
        Instant signedIn = NOW.minusSeconds(300);
        assertThat(issuedClaims(signedIn.getEpochSecond())).containsEntry("auth_time", signedIn.getEpochSecond()).containsEntry("support", true);
    }

    @Test void AUTH_01_aConsoleTokenWithoutASignInTimeGivesASupportTokenWithoutOne() {
        assertThat(issuedClaims(null)).doesNotContainKey("auth_time");
    }
}
