package com.agilityhub.core.identity.application;

import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationWelcomeDeliveryTest {
    final AccountRepository accounts = mock(AccountRepository.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final MagicLinkService links = mock(MagicLinkService.class);
    final SystemNotificationService notifications = mock(SystemNotificationService.class);
    final CensusClubSettings clubs = mock(CensusClubSettings.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC);
    final MigrationWelcomeDelivery delivery = new MigrationWelcomeDelivery(accounts, memberships, links, notifications, clubs, clock);
    Account account(Account.Status status, NotificationAccounts.EmailStatus emailStatus) {
        return new Account("account", "fictional@example.test", "Fictional", "ca", null, Set.of(), status, null, Map.of(), true, clock.instant(), emailStatus);
    }
    Membership membership(Membership.Status status, String member, Set<Role> roles) {
        return new Membership("membership", "account", "club", member, roles, status, Role.MEMBER);
    }
    @BeforeEach void setup() {
        TenantContext.open("club");
        when(accounts.findById("account")).thenReturn(Optional.of(account(Account.Status.ACTIVE, null)));
        when(memberships.findByAccountId("account")).thenReturn(Optional.of(membership(Membership.Status.ACTIVE, "member", Set.of(Role.MEMBER))));
        when(accounts.migrationWelcome("account")).thenReturn(new AccountRepository.WelcomeState(null, null));
        when(clubs.appHost()).thenReturn("club.example.test"); when(clubs.name()).thenReturn("Fictional Club");
    }
    @AfterEach void clear() { TenantContext.clear(); }
    MigrationWelcomeDelivery.Result run(boolean dry) { return delivery.deliver("member", "account", null, null, dry); }
    @Test void T_18_09_ineligibleAndSuppressedAccountsNeverClaimOrSend() {
        when(accounts.findById("account")).thenReturn(Optional.empty());
        assertThat(run(false)).isEqualTo(MigrationWelcomeDelivery.Result.INELIGIBLE);
        when(accounts.findById("account")).thenReturn(Optional.of(account(Account.Status.BLOCKED, null)));
        assertThat(run(false)).isEqualTo(MigrationWelcomeDelivery.Result.INELIGIBLE);
        when(accounts.findById("account")).thenReturn(Optional.of(account(Account.Status.ACTIVE, NotificationAccounts.EmailStatus.BOUNCED)));
        assertThat(run(false)).isEqualTo(MigrationWelcomeDelivery.Result.SUPPRESSED);
        when(accounts.findById("account")).thenReturn(Optional.of(account(Account.Status.ACTIVE, null)));
        when(memberships.findByAccountId("account")).thenReturn(Optional.empty());
        assertThat(run(false)).isEqualTo(MigrationWelcomeDelivery.Result.INELIGIBLE);
        when(memberships.findByAccountId("account")).thenReturn(Optional.of(membership(Membership.Status.SUSPENDED,"member",Set.of(Role.MEMBER))));
        assertThat(run(false)).isEqualTo(MigrationWelcomeDelivery.Result.INELIGIBLE);
        when(memberships.findByAccountId("account")).thenReturn(Optional.of(membership(Membership.Status.ACTIVE,"other",Set.of(Role.MEMBER))));
        assertThat(run(false)).isEqualTo(MigrationWelcomeDelivery.Result.INELIGIBLE);
        verify(accounts, never()).claimMigrationWelcome(anyString(), anyString(), any()); verifyNoInteractions(links);
    }
    @Test void T_18_09_dryRunAndLostClaimNeverCreateLinks() {
        assertThat(run(true)).isEqualTo(MigrationWelcomeDelivery.Result.ELIGIBLE);
        assertThat(run(false)).isEqualTo(MigrationWelcomeDelivery.Result.REVIEW_REQUIRED);
        verifyNoInteractions(links);
    }
    @Test void T_18_09_differentClubOrAmbiguousAttemptNeverResends() {
        when(accounts.migrationWelcome("account")).thenReturn(new AccountRepository.WelcomeState(null,"another-club-delivery"));
        assertThat(run(false)).isEqualTo(MigrationWelcomeDelivery.Result.REVIEW_REQUIRED);
        verifyNoInteractions(links, notifications);
    }
    @Test void T_18_09_staffPriorityRequiresAnActiveStaffRole() {
        assertThat(delivery.staff("account")).isFalse();
        when(memberships.findByAccountId("account")).thenReturn(Optional.of(membership(Membership.Status.ACTIVE,"member",Set.of(Role.MEMBER,Role.ADMIN))));
        assertThat(delivery.staff("account")).isTrue();
        when(memberships.findByAccountId("account")).thenReturn(Optional.of(membership(Membership.Status.ACTIVE,"member",Set.of(Role.MEMBER,Role.INSTRUCTOR))));
        assertThat(delivery.staff("account")).isTrue();
        when(memberships.findByAccountId("account")).thenReturn(Optional.of(membership(Membership.Status.SUSPENDED,"member",Set.of(Role.MEMBER,Role.ADMIN))));
        assertThat(delivery.staff("account")).isFalse();
    }
}
