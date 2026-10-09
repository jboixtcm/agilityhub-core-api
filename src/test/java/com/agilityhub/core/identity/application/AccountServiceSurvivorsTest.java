package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.domain.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link AccountService} (S01 R-01-01, R-01-14; T-01-01, T-01-23): an account created with an initial
 * password records when that password was set (and one without a password does not), and a name of exactly 200 characters is valid.
 * Collaborators are mocks; fictional data.
 */
class AccountServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    /** A fictional Argon2id hash of the shape PasswordHasher.java:11,15 produces for a seed password (ClubAccountService.java:39-41). */
    static final String HASH = "$argon2id$v=19$m=19456,t=2,p=1$ZmljdGlvbmFsLXNhbHQxNg$ZmljdGlvbmFsLWFyZ29uMmlkLWhhc2gtMzJieXRlcyE";

    final AccountRepository accounts = mock(AccountRepository.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final EventPublisher events = mock(EventPublisher.class);
    final AccountService service = new AccountService(accounts, transactions, events, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach void setUp() {
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
    }

    static Account stored(String hash) {
        return new Account("acc-1", "laura@example.test", "Laura Example", "ca", hash, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, hash == null ? null : NOW, 0), Map.of(), false, NOW);
    }

    /**
     * The seed path of ClubAccountService.java:39-41 (CONSOLE, the seed's onboarding flag). The upsert stores the candidate itself
     * (AccountRepository.java:46-56), so the re-read after `createIfAbsent` returns that candidate (AccountService.java:41-42).
     */
    private Account.Security createdSecurity(String hash) {
        var inserted = new AtomicReference<Account>();
        when(accounts.findByEmail("laura@example.test")).thenAnswer(call -> Optional.ofNullable(inserted.get()));
        when(accounts.createIfAbsent(any())).thenAnswer(call -> { inserted.set(call.getArgument(0)); return true; });
        service.getOrCreate("laura@example.test", "Laura Example", "ca", Account.Source.CONSOLE, hash, false);
        var candidate = ArgumentCaptor.forClass(Account.class);
        verify(accounts).createIfAbsent(candidate.capture());
        return candidate.getValue().security();
    }

    @Test void T_01_01_anAccountCreatedWithAnInitialPasswordRecordsWhenItWasSet() {
        assertThat(createdSecurity(HASH).passwordChangedAt()).isEqualTo(NOW);
    }

    @Test void T_01_01_anAccountCreatedWithoutPasswordHasNoPasswordChangeDate() {
        assertThat(createdSecurity(null).passwordChangedAt()).isNull();
    }

    @Test void T_01_23_aNameOf200CharactersIsAccepted() {
        when(accounts.findById("acc-1")).thenReturn(Optional.of(stored(null)));
        String name = "L".repeat(200);

        service.patch("acc-1", null, name);

        verify(accounts).patch("acc-1", null, name);
    }

    @Test void T_01_23_aNameOf201CharactersIsAValidationError() {
        assertThatThrownBy(() -> service.patch("acc-1", null, "L".repeat(201)))
                .isInstanceOf(ApiException.class).hasMessage("VALIDATION_ERROR");
        verify(accounts, never()).patch(any(), any(), any());
    }
}
