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
 * E11-T06 PIT survivors of {@link MembershipService#setRoles} (S01 R-01-07, T-01-05; club-as-code `accounts`/`admins`): a role
 * change serialises the account's sessions, and the stored membership keeps a remembered default profile that is still available
 * and moves to the next version. The assertions read the membership handed to `MembershipRepository.replace`, never the returned
 * one: no production caller reads what `setRoles`, `suspend` or `resume` return (ClubAdminService.java:39 and
 * ClubAccountService.java:42 discard it; `suspend`/`resume` have no production caller). Collaborators are mocks; fictional data.
 */
class MembershipServiceSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant CREATED = Instant.parse("2026-01-10T09:00:00Z");

    final MembershipRepository memberships = mock(MembershipRepository.class);
    final AccountService accounts = mock(AccountService.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final EventPublisher events = mock(EventPublisher.class);
    final AccountRepository accountRepository = mock(AccountRepository.class);
    final AccountSessionRepository sessions = mock(AccountSessionRepository.class);
    final MembershipService service = new MembershipService(memberships, accounts, transactions, events, Clock.fixed(NOW, ZoneOffset.UTC),
            accountRepository, sessions);

    @BeforeEach void setUp() {
        TenantContext.open(CLUB);
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        when(accounts.active("acc-1")).thenReturn(new Account("acc-1", "laura@example.test", "Laura Example", "ca", null, Set.of(),
                Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(), false, CREATED));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    private Membership replaced() {
        var captor = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).replace(captor.capture());
        return captor.getValue();
    }

    /** A seed account's first roles (ClubAccountService.java:42, `roles: [INSTRUCTOR]`). */
    @Test void E11_T06_theFirstRolesOfAnAccountCreateAnActiveMembershipAndSerialiseItsSessions() {
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.empty());

        service.setRoles("acc-1", Set.of(Role.INSTRUCTOR));

        var stored = replaced();
        assertThat(stored.status()).isEqualTo(Membership.Status.ACTIVE);
        assertThat(stored.roles()).containsExactly(Role.INSTRUCTOR);
        assertThat(stored.version()).isZero();
        verify(accountRepository).touchSessions("acc-1");
    }

    /**
     * A club-as-code staff membership (MembershipService.java:58-63: no member, version 0, then two role changes) whose holder chose
     * and remembered the ADMIN profile (TokenService.java:153, MembershipRepository.java:31-34); the seed then drops INSTRUCTOR
     * (ClubAccountService.java:42).
     */
    @Test void T_01_05_aRemovedRoleKeepsTheRememberedDefaultProfileThatIsStillAvailableAtTheNextVersion() {
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(new Membership("membership-1", "acc-1", CLUB, null,
                Set.of(Role.ADMIN, Role.INSTRUCTOR), Membership.Status.ACTIVE, Role.ADMIN, true, null, CREATED, null, null, 2, CREATED,
                null, null)));

        service.setRoles("acc-1", Set.of(Role.ADMIN));

        var stored = replaced();
        assertThat(stored.roles()).containsExactly(Role.ADMIN);
        assertThat(stored.defaultProfile()).isEqualTo(Role.ADMIN);
        assertThat(stored.rememberProfile()).isTrue();
        assertThat(stored.version()).isEqualTo(3);
        assertThat(stored.createdAt()).isEqualTo(CREATED);
    }
}
