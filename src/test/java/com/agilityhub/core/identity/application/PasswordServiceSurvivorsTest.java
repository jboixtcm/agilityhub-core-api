package com.agilityhub.core.identity.application;

import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.AccountSessionRepository;
import com.agilityhub.core.identity.persistence.RefreshToken;
import com.agilityhub.core.shared.application.EventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link PasswordService} (S01 R-01-05; T-01-09): a new password of exactly `auth.passwordMinLength`
 * characters is accepted, and a change clears the session's reset mark and revokes the account's other sessions. Collaborators
 * are mocks; fictional data.
 */
class PasswordServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    // A Learn-imported bcrypt hash, the only bcrypt form the import accepts (LearnImportService.java:113).
    static final String OLD_HASH = "$2y$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0";
    static final String NEW_HASH = "$argon2id$v=19$m=19456,t=2,p=1$ZXhhbXBsZXNhbHQ$ZXhhbXBsZWhhc2hleGFtcGxlaGFzaA";

    final IdentityService identities = mock(IdentityService.class);
    final AccountRepository accounts = mock(AccountRepository.class);
    final AccountSessionRepository sessions = mock(AccountSessionRepository.class);
    final TokenService tokens = mock(TokenService.class);
    final PasswordHasher passwords = mock(PasswordHasher.class);
    final CompromisedPasswords compromised = mock(CompromisedPasswords.class);
    final AuthSettings settings = mock(AuthSettings.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final EventPublisher events = mock(EventPublisher.class);
    final SystemNotificationService notifications = mock(SystemNotificationService.class);
    final PasswordService service = new PasswordService(identities, accounts, sessions, tokens, passwords, compromised, settings, transactions,
            events, notifications, Clock.fixed(NOW, ZoneOffset.UTC));
    final Account account = new Account("acc-1", "laura@example.test", "Laura Example", "ca", OLD_HASH, Set.of(), Account.Status.ACTIVE,
            new Account.Security(0, null, NOW.minus(Duration.ofDays(60)), 0), Map.of(), false, NOW.minus(Duration.ofDays(400)));

    @BeforeEach void setUp() {
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        when(identities.current("acc-1")).thenReturn(new IdentityService.Session(account, null));
        when(passwords.verify("Old-passw0rd", OLD_HASH)).thenReturn(true);
        when(settings.integer("auth.passwordMinLength")).thenReturn(8);
        when(settings.enabled("auth.checkCompromisedPasswords")).thenReturn(true);
        when(tokens.requireFamily("acc-1", "id-web", "fam-1", 0)).thenReturn(new RefreshToken("rt-1", "hash-1", "acc-1", null, "id-web", "fam-1", 0,
                NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(29)), NOW.minusSeconds(60), null, null));
    }

    private void change(String next) {
        when(passwords.hash(next)).thenReturn(NEW_HASH);
        service.change("acc-1", "id-web", "fam-1", "Old-passw0rd", next, next);
    }

    @Test void T_01_09_aNewPasswordOfExactlyTheMinimumLengthIsAccepted() {
        assertThat("Kq7#mZ2p").hasSize(8);

        change("Kq7#mZ2p");

        verify(accounts).password("acc-1", NEW_HASH, NOW);
        verify(notifications).send(eq("N-26"), eq("acc-1"), any());
    }

    @Test void T_01_09_aChangeClearsTheSessionsPasswordResetMark() {
        change("Kq7#mZ2p-longer");

        verify(sessions).clearPasswordReset("acc-1", "fam-1");
    }

    @Test void T_01_09_aChangeRevokesTheAccountsOtherSessions() {
        change("Kq7#mZ2p-longer");

        verify(sessions).revokeOthers("acc-1", "fam-1", NOW);
        verify(sessions).preserveFamily("acc-1", "fam-1", 1);
    }
}
