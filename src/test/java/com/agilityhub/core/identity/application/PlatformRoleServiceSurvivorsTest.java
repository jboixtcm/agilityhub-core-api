package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link PlatformRoleService} (platform console; CLI `identity:grant-platform-admin`): an administrator reads
 * an account's platform roles, and the bootstrap grant serialises role changes before writing and answers the granted roles.
 * Collaborators are mocks; fictional data.
 */
class PlatformRoleServiceSurvivorsTest {
    static final Instant CREATED = Instant.parse("2026-01-10T09:00:00Z");

    final AccountRepository accounts = mock(AccountRepository.class);
    final PlatformRoleService service = new PlatformRoleService(accounts);

    static Account account(String id, String email, Set<Account.PlatformRole> roles) {
        return new Account(id, email, "Example Person", "ca", null, roles, Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(),
                false, CREATED);
    }

    @Test void E11_T06_aPlatformAdministratorReadsAnotherAdministratorsPlatformRoles() {
        when(accounts.findById("acc-admin")).thenReturn(Optional.of(account("acc-admin", "admin@example.test", Set.of(Account.PlatformRole.AGILITYHUB_ADMIN))));
        when(accounts.findById("acc-2")).thenReturn(Optional.of(account("acc-2", "marc@example.test", Set.of(Account.PlatformRole.AGILITYHUB_ADMIN))));

        assertThat(service.get("acc-admin", "acc-2")).containsExactly("AGILITYHUB_ADMIN");
    }

    @Test void E11_T06_theBootstrapGrantSerialisesRoleChangesAndAnswersTheGrantedRole() {
        when(accounts.findById("acc-2")).thenReturn(Optional.of(account("acc-2", "marc@example.test", Set.of())),
                Optional.of(account("acc-2", "marc@example.test", Set.of(Account.PlatformRole.AGILITYHUB_ADMIN))));

        assertThat(service.grant("acc-2")).containsExactly("AGILITYHUB_ADMIN");

        InOrder order = inOrder(accounts);
        order.verify(accounts).serializePlatformRoles();
        order.verify(accounts).platformRoles("acc-2", Set.of(Account.PlatformRole.AGILITYHUB_ADMIN));
    }
}
