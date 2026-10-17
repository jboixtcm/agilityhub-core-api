package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.AccountSessionRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors (session 10) of {@link MembershipService} as S17's console reads it (R-17-09, T-17-08):
 * ConsoleAdministrators.invite reads what `setRoles` returns and, for a suspended administrator, what `resume` returns
 * (ConsoleAdministrators.java:40-41,44). Re-inviting a removed administrator (roles already include ADMIN, still SUSPENDED) returns
 * the stored membership unchanged; resuming it returns the ACTIVE membership it stored. Collaborators are mocks; fictional data.
 */
class MembershipServiceSurvivors3Test {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant CREATED = Instant.parse("2026-01-10T09:00:00Z");

    final MembershipRepository memberships = mock(MembershipRepository.class);
    final AccountService accounts = mock(AccountService.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final AccountRepository accountRepository = mock(AccountRepository.class);
    final AccountSessionRepository sessions = mock(AccountSessionRepository.class);
    final MembershipService service = new MembershipService(memberships, accounts, transactions, mock(EventPublisher.class),
            Clock.fixed(NOW, ZoneOffset.UTC), accountRepository, sessions);

    /** A console administrator removed by ConsoleAdministrators.remove: MembershipService.suspend keeps the roles. */
    final Membership removed = new Membership("membership-1", "acc-1", CLUB, "member-1", Set.of(Role.MEMBER, Role.ADMIN),
            Membership.Status.SUSPENDED, null, false, null, CREATED, NOW.minusSeconds(86_400), null, 3, NOW.minusSeconds(3_600), null, null);

    @BeforeEach void setUp() {
        TenantContext.clear();
        TenantContext.open(CLUB);
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        when(accounts.active("acc-1")).thenReturn(new Account("acc-1", "laura@example.test", "Laura Example", "ca", null, Set.of(),
                Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(), false, CREATED));
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(removed));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void T_17_08_reinvitingARemovedAdministratorReturnsItsUnchangedMembership() {
        var result = service.setRoles("acc-1", Set.of(Role.MEMBER, Role.ADMIN));

        assertThat(result).isSameAs(removed);
        verify(memberships, never()).replace(any());
    }

    @Test void T_17_08_resumingARemovedAdministratorReturnsTheActiveMembershipItStored() {
        var result = service.resume("acc-1");

        var stored = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).replace(stored.capture());
        assertThat(result).isNotNull().isEqualTo(stored.getValue());
        assertThat(result.status()).isEqualTo(Membership.Status.ACTIVE);
        assertThat(result.roles()).containsExactlyInAnyOrder(Role.MEMBER, Role.ADMIN);
        assertThat(result.version()).isEqualTo(4);
        verify(sessions, never()).revokeClub(any(), any(), any());
    }
}
