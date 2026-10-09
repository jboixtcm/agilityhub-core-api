package com.agilityhub.core.identity.application;

import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.MagicLinkToken;
import com.agilityhub.core.identity.persistence.MagicLinkTokenRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TenantHostResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link MagicLinkService#createAndSend} (S01 R-01-04, T-01-03, T-01-08; S04 §8 N-02/N-39): a
 * redelivered link whose notification already completed is not sent again; the RECOGNITION link may carry the fixed
 * `/gossos/nou` redirect but a LOGIN link may not; a club link needs an active membership (a suspended one gets nothing); the
 * account's sessions are serialised; N-02 (WELCOME) and N-39 (RECOGNITION) carry the right link, expiry in minutes, the census
 * variables of the signup and the account's locale. Collaborators are mocks, the `clubs-app` client is registered as in
 * `application.yml`; fictional data.
 */
class MagicLinkServiceSurvivorsTest {
    static final String CLUB = "club-a";
    static final String HOST = "club-a.example.test";
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Map<String, Object> SIGNUP = Map.of("member_first_name", "Laura Maria", "gender", "FEMALE", "club_name", "Club Example",
            "locale", "es");

    final AccountRepository accounts = mock(AccountRepository.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final MagicLinkTokenRepository tokens = mock(MagicLinkTokenRepository.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final AuthSettings settings = mock(AuthSettings.class);
    final EventPublisher events = mock(EventPublisher.class);
    final SystemNotificationService notifications = mock(SystemNotificationService.class);
    final TenantHostResolver hosts = mock(TenantHostResolver.class);
    /** `clubs-app` as OidcClientConfiguration.java:42-46 registers it from application.yml:112-117 (public client, its grants). */
    final RegisteredClient clubsApp = clubsApp();
    static RegisteredClient clubsApp() {
        var client = RegisteredClient.withId("clubs-app").clientId("clubs-app")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .redirectUri("https://clubs.example.test/oidc/callback").postLogoutRedirectUri("https://clubs.example.test/");
        List.of("openid", "profile", "email", "memberships", "offline_access").forEach(client::scope);
        List.of("authorization_code", "password", MagicLinkService.GRANT, "urn:agilityhub:grant:handoff", "refresh_token")
                .forEach(grant -> client.authorizationGrantType(new AuthorizationGrantType(grant)));
        return client.build();
    }
    final MagicLinkService service = new MagicLinkService(accounts, memberships, tokens, transactions, settings, events, notifications,
            new InMemoryRegisteredClientRepository(clubsApp), hosts, mock(Executor.class), Clock.fixed(NOW, ZoneOffset.UTC),
            "https://id.example.test");

    @BeforeEach void setUp() {
        TenantContext.open(CLUB);
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        when(hosts.resolve(HOST)).thenReturn(Optional.of(CLUB));
        // Catalog defaults (catalog.yaml:17-19, 1057-1059).
        when(settings.integer("auth.welcomeLinkDays")).thenReturn(7);
        when(settings.integer("auth.magicLinkMinutes")).thenReturn(15);
        // A signup account (SignupIdentityService.java:26-27).
        var account = new Account("acc-1", "laura@example.test", "Laura Example", "ca", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, NOW.minusSeconds(86_400), null, null, Account.Source.SIGNUP,
                null, null);
        when(accounts.findByEmail("laura@example.test")).thenReturn(Optional.of(account));
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account));
        member(Membership.Status.ACTIVE);
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    /**
     * The member's membership: ACTIVE as SignupIdentityService.java:39 creates it, or SUSPENDED without roles after the member left
     * (RoleAssignmentService.java:184-193 via TeamMembershipService.java:71-72).
     */
    private void member(Membership.Status status) {
        boolean active = status == Membership.Status.ACTIVE;
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(new Membership("membership-1", "acc-1", CLUB, "member-1",
                active ? Set.of(Role.MEMBER) : Set.of(), status, active ? Role.MEMBER : null, false, null, NOW.minusSeconds(86_400), null,
                null, active ? 0 : 1, NOW.minusSeconds(3600), null, null)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> localized(String deliveryId, String code, String locale) {
        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(notifications).sendOnceLocalized(eq(deliveryId), eq(code), eq("acc-1"), eq(locale), variables.capture());
        return variables.getValue();
    }

    @Test void T_01_03_aRedeliveredLinkWhoseNotificationAlreadyCompletedIsNotSentAgain() {
        when(notifications.completed("event-1")).thenReturn(true);

        service.createAndSend("laura@example.test", MagicLinkToken.Purpose.ACCESS_RESEND, "clubs-app", null, HOST, null, null, "event-1");

        verifyNoInteractions(tokens);
        verify(notifications, never()).sendOnce(any(), any(), any(), any());
    }

    @Test void T_01_08_aClubLinkSerialisesTheAccountSessionsAndIsSent() {
        service.createAndSend("laura@example.test", MagicLinkToken.Purpose.LOGIN, "clubs-app", null, HOST, null, null);

        verify(accounts).touchSessions("acc-1");
        verify(tokens).insert(any(MagicLinkToken.class));
        verify(notifications).send(eq("N-25"), eq("acc-1"), any());
    }

    @Test void T_01_08_aSuspendedMembershipGetsNoClubLink() {
        member(Membership.Status.SUSPENDED);

        service.createAndSend("laura@example.test", MagicLinkToken.Purpose.LOGIN, "clubs-app", null, HOST, null, null);

        verifyNoInteractions(tokens, notifications);
    }

    @Test void T_01_08_aLoginLinkCannotCarryTheRecognitionRedirect() {
        service.createAndSend("laura@example.test", MagicLinkToken.Purpose.LOGIN, "clubs-app", "/gossos/nou", HOST, null, null);

        verifyNoInteractions(tokens, notifications);
    }

    @Test void T_01_03_theRecognitionLinkIsN39WithTheNewDogRedirectTheLoginExpiryAndTheSignupVariables() {
        service.createAndSend("laura@example.test", MagicLinkToken.Purpose.RECOGNITION, "clubs-app", "/gossos/nou", HOST, null, null,
                "event-2", SIGNUP);

        var variables = localized("event-2", "N-39", "ca");
        assertThat((String) variables.get("link")).startsWith("https://club-a.example.test/activacio?t=").endsWith("&redirect=%2Fgossos%2Fnou");
        assertThat(variables).containsEntry("expires_minutes", 15).containsEntry("member_first_name", "Laura Maria")
                .containsEntry("gender", "FEMALE").containsEntry("club_name", "Club Example");
    }

    @Test void T_01_03_theWelcomeLinkIsN02WithoutRedirectValidForTheWelcomeDaysInMinutes() {
        service.createAndSend("laura@example.test", MagicLinkToken.Purpose.WELCOME, "clubs-app", null, HOST, null, null, "event-3", SIGNUP);

        var variables = localized("event-3", "N-02", "ca");
        assertThat((String) variables.get("link")).startsWith("https://club-a.example.test/activacio?t=").doesNotContain("redirect");
        assertThat(variables).containsEntry("expires_minutes", 7 * 1440);
    }
}
