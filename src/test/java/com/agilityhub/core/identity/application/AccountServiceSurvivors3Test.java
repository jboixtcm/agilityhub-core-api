package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivor (session 10, NO_COVERAGE) of {@link AccountService#active}: a token whose subject has no account is
 * UNAUTHENTICATED (the identity error of every caller: MembershipService, ConsoleAdministrators, AccountService.patch), never a
 * NullPointerException. Collaborators are mocks; fictional data.
 */
class AccountServiceSurvivors3Test {
    final AccountRepository accounts = mock(AccountRepository.class);
    final AccountService service = new AccountService(accounts, mock(IdentityTransactions.class), mock(EventPublisher.class),
            Clock.fixed(Instant.parse("2026-10-05T08:00:00Z"), ZoneOffset.UTC));

    @Test void E11_T06_anAccountIdWithoutAccountIsUnauthenticated() {
        when(accounts.findById("acc-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.active("acc-404"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
}
