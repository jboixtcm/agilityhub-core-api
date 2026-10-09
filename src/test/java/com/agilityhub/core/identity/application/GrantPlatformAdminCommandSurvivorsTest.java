package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivor of {@link GrantPlatformAdminCommand#run} (deployment CLI `identity:grant-platform-admin`): the operator is
 * told that the role is ensured. Collaborators are mocks; fictional data.
 */
@ExtendWith(OutputCaptureExtension.class)
class GrantPlatformAdminCommandSurvivorsTest {
    final AccountRepository accounts = mock(AccountRepository.class);
    final PlatformRoleService roles = mock(PlatformRoleService.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final GrantPlatformAdminCommand command = new GrantPlatformAdminCommand(accounts, roles, transactions);

    @Test void E11_T06_grantingThePlatformAdminRoleConfirmsItOnTheConsole(CapturedOutput output) {
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        when(accounts.findByEmail("laura@example.test")).thenReturn(Optional.of(new Account("acc-1", "laura@example.test", "Laura Example", "ca",
                null, Set.of(), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(), false,
                Instant.parse("2026-10-05T08:00:00Z"))));
        // PlatformRoleService.java:34-37,48-49,58: the account's platform roles after the grant.
        when(roles.grant("acc-1")).thenReturn(Set.of("AGILITYHUB_ADMIN"));

        command.run(new DefaultApplicationArguments("laura@example.test"));

        verify(roles).grant("acc-1");
        assertThat(output.getOut()).contains("Platform administrator role ensured.");
    }
}
