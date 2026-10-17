package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.shared.application.DemoSeedStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors (session 11) of {@link DemoPlatformAccountsSeeder} (S17 R-17-12, T-17-10; E10-T03 step 4): the seed is
 * insert-only, so an account that already is an AgilityHub administrator gets no write, and a seed account never prints its
 * password or e-mail. Collaborators are mocks; fictional data.
 */
class DemoPlatformAccountsSeederSurvivors3Test {
    static final Instant NOW = Instant.parse("2026-10-17T08:00:00Z");

    final AccountRepository accounts = mock(AccountRepository.class);
    final AccountService accountService = mock(AccountService.class);
    final DemoPlatformAccountsSeeder seeder = new DemoPlatformAccountsSeeder(new ObjectMapper(), accountService, accounts,
            mock(PasswordHasher.class), mock(SeedPasswordPolicy.class));

    private static DemoSeedStep.Input input() {
        var platform = Map.of("accounts", List.of(Map.of("email", "pat.support@example.test", "name", "Pat Support", "locale", "ca")));
        return new DemoSeedStep.Input(Map.of("platform", platform), 1L, LocalDate.of(2026, 10, 12), "admin-1", Set.of(), List.of(),
                LocalDate.of(2026, 10, 17), false, Set.of());
    }

    @Test void T_17_10_R_17_12_anAccountThatIsAlreadyAPlatformAdministratorGetsNoWrite() {
        when(accounts.findByEmail("pat.support@example.test")).thenReturn(Optional.of(new Account("platform-1", "pat.support@example.test",
                "Pat Support", "ca", null, Set.of(Account.PlatformRole.AGILITYHUB_ADMIN), Account.Status.ACTIVE, null, Map.of(), false, NOW)));

        assertThat(seeder.apply(input())).isEqualTo(Map.of("platformAccounts", 1));

        verify(accounts, never()).platformRoles(anyString(), any());
        verifyNoInteractions(accountService);
    }

    @Test void T_17_10_R_17_12_aSeedAccountNeverPrintsItsCredentials() {
        var spec = new DemoPlatformAccountsSeeder.AccountSpec("pat.support@example.test", "Pat Support", "ca", "Fictional-Pass-1");

        assertThat(spec.toString()).isEqualTo("AccountSpec[redacted]");
    }
}
