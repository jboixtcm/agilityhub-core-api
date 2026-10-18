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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * E11-T06 PIT survivors (session 10) of {@link MagicLinkService#sendAdminInvitation} (S17 R-17-09, T-17-08: N-53; S01 R-01-04):
 * the invitation serialises the account's sessions, stores a WELCOME link valid for `auth.welcomeLinkDays`, trims the older WELCOME
 * links and sends N-53 once, completing normally when the delivery was sent; an administrator removed before the outbox delivered
 * the invitation (membership SUSPENDED) gets nothing. Collaborators are mocks; fictional data.
 */
class MagicLinkServiceSurvivors3Test {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final AccountRepository accounts = mock(AccountRepository.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final MagicLinkTokenRepository tokens = mock(MagicLinkTokenRepository.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final AuthSettings settings = mock(AuthSettings.class);
    final SystemNotificationService notifications = mock(SystemNotificationService.class);
    final MagicLinkService service = new MagicLinkService(accounts, memberships, tokens, transactions, settings, mock(EventPublisher.class),
            notifications, new InMemoryRegisteredClientRepository(idWeb()), mock(TenantHostResolver.class),
            mock(Executor.class), Clock.fixed(NOW, ZoneOffset.UTC), "https://id.example.test",
            mock(com.agilityhub.core.shared.application.SignupCapabilities.class));

    /** The invitation never reads the client registry; the repository only needs one registered client. */
    static RegisteredClient idWeb() {
        return RegisteredClient.withId("id-web").clientId("id-web").clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(new AuthorizationGrantType(MagicLinkService.GRANT)).redirectUri("https://id.example.test/oidc/callback")
                .scope("openid").build();
    }

    @BeforeEach void setUp() {
        TenantContext.clear();
        TenantContext.open(CLUB);
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        when(settings.integer("auth.welcomeLinkDays")).thenReturn(7);
        // A console account (ConsoleAdministrators.invite: AccountService.getOrCreate with Source.CONSOLE).
        when(accounts.findById("acc-2")).thenReturn(Optional.of(new Account("acc-2", "marc@example.test", "Marc Example", "ca", null, Set.of(),
                Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(), false, NOW.minusSeconds(60), null, null,
                Account.Source.CONSOLE, null, null)));
        when(notifications.sent("delivery-1")).thenReturn(true);
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    private void membership(Membership.Status status) {
        when(memberships.findByAccountId("acc-2")).thenReturn(Optional.of(new Membership("ms-2", "acc-2", CLUB, "member-2",
                Set.of(Role.MEMBER, Role.ADMIN), status, null, false, null, NOW.minusSeconds(86_400), null, null, 1, NOW.minusSeconds(60), null, null)));
    }

    @Test void T_17_08_theInvitationStoresAWelcomeLinkTrimsTheOlderOnesAndSendsN53() {
        membership(Membership.Status.ACTIVE);

        assertThatCode(() -> service.sendAdminInvitation("delivery-1", "acc-2", "Club Example", "Pat Example")).doesNotThrowAnyException();

        verify(accounts).touchSessions("acc-2");
        var token = ArgumentCaptor.forClass(MagicLinkToken.class);
        verify(tokens).insert(token.capture());
        assertThat(token.getValue().purpose()).isEqualTo(MagicLinkToken.Purpose.WELCOME);
        assertThat(token.getValue().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        verify(tokens).trim("acc-2", MagicLinkToken.Purpose.WELCOME, NOW, token.getValue().id());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(notifications).sendOnce(eq("delivery-1"), eq("N-53"), eq("acc-2"), variables.capture());
        assertThat(variables.getValue()).containsEntry("club_name", "Club Example").containsEntry("inviter_name", "Pat Example");
        assertThat((String) variables.getValue().get("link")).startsWith("https://id.example.test/magic-link?t=");
        assertThat(TenantContext.current()).isEqualTo(CLUB);
    }

    @Test void T_17_08_anAdministratorRemovedBeforeTheDeliveryGetsNoInvitation() {
        // ConsoleAdministrators.remove suspends the membership and keeps its roles (MembershipService.status).
        membership(Membership.Status.SUSPENDED);

        service.sendAdminInvitation("delivery-1", "acc-2", "Club Example", "Pat Example");

        verifyNoInteractions(tokens);
        verify(accounts, never()).touchSessions(any());
        verify(notifications, never()).sendOnce(any(), any(), any(), any());
    }
}
