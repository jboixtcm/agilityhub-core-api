package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountMembershipRepository;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.MagicLinkToken;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditActor;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.RateLimits;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AUTH-02 (owner decision 13-11-2026, S03 R-03-06b): the identity side of an ADMIN's login e-mail change. The checks before any
 * link ({@link CensusIdentityService#checkLoginEmailChange}), D10's view of the login e-mail ({@link CensusIdentityService#login})
 * and the change a used link applies ({@link AccountEmailChangeService}). Mocks and a real {@link RateLimits}; fictional data.
 */
class LoginEmailChangeTest {
    static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    final AccountRepository accounts = mock(AccountRepository.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final AccountMembershipRepository clubsOfAccount = mock(AccountMembershipRepository.class);
    final MagicLinkService links = mock(MagicLinkService.class);
    final CensusClubSettings clubs = mock(CensusClubSettings.class);
    final RateLimits limits = new RateLimits(true, Map.of(
            RateLimits.Route.MAGIC_LINK_EMAIL, new RateLimits.Limit(10, Duration.ofHours(1)),
            RateLimits.Route.MAGIC_LINK_IP, new RateLimits.Limit(60, Duration.ofHours(1))), Clock.fixed(NOW, ZoneOffset.UTC));
    final CensusIdentityService census = new CensusIdentityService(accounts, memberships, links, limits, clubs, clubsOfAccount);

    @BeforeEach void setUp() {
        TenantContext.open("club-a");
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account(Set.of(), NotificationAccounts.EmailStatus.COMPLAINED)));
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(new Membership("membership-1", "acc-1", "club-a", "member-1",
                Set.of(Role.MEMBER), Membership.Status.ACTIVE, Role.MEMBER)));
        when(clubsOfAccount.countNotErased("acc-1")).thenReturn(1L);
    }
    @AfterEach void tearDown() { TenantContext.clear(); }

    static Account account(Set<Account.PlatformRole> roles, NotificationAccounts.EmailStatus status) {
        return new Account("acc-1", "laura@example.test", "Laura Example", "ca", null, roles, Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, NOW.minusSeconds(86_400), status);
    }

    @Test void AUTH_02_theNewAddressIsNormalisedAndChecked() {
        assertThat(census.checkLoginEmailChange("acc-1", "  Laura.New@Example.TEST ", "203.0.113.5")).isEqualTo("laura.new@example.test");
    }

    @Test void AUTH_02_theCurrentAddressIsNoChange() {
        assertThatThrownBy(() -> census.checkLoginEmailChange("acc-1", "LAURA@example.test", "203.0.113.5"))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure).hasMessage("VALIDATION_ERROR");
                    assertThat(failure.details()).containsEntry("fieldErrors", List.of(Map.of("field", "email", "code", "UNCHANGED")));
                });
    }

    @Test void AUTH_02_anAccountOfAnotherClubTooOrWithAPlatformRoleIsNotTheClubsToChange() {
        when(clubsOfAccount.countNotErased("acc-1")).thenReturn(2L);
        assertThatThrownBy(() -> census.checkLoginEmailChange("acc-1", "new@example.test", "203.0.113.5")).hasMessage("FORBIDDEN");
        when(clubsOfAccount.countNotErased("acc-1")).thenReturn(1L);
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account(Set.of(Account.PlatformRole.AGILITYHUB_ADMIN), null)));
        assertThatThrownBy(() -> census.checkLoginEmailChange("acc-1", "new@example.test", "203.0.113.5")).hasMessage("FORBIDDEN");
    }

    @Test void AUTH_02_aMemberWithoutAnActiveMembershipHasNoLoginToChange() {
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(new Membership("membership-1", "acc-1", "club-a", "member-1",
                Set.of(), Membership.Status.SUSPENDED, null)));
        assertThatThrownBy(() -> census.checkLoginEmailChange("acc-1", "new@example.test", "203.0.113.5")).hasMessage("MEMBER_NOT_ACTIVE");
    }

    @Test void AUTH_02_theResendLimitsApplyToTheNewAddress() {
        for (int i = 0; i < 10; i++) { census.checkLoginEmailChange("acc-1", "new@example.test", "203.0.113." + i); }
        assertThatThrownBy(() -> census.checkLoginEmailChange("acc-1", "NEW@example.test", "198.51.100.7")).hasMessage("RATE_LIMITED");
        assertThatCode(() -> census.checkLoginEmailChange("acc-1", "other@example.test", "198.51.100.7")).doesNotThrowAnyException();
    }

    @Test void AUTH_02_theLoginOfD10ShowsTheAddressAndItsSuppressionMark() {
        assertThat(census.login("acc-1")).containsEntry("email", "laura@example.test").containsEntry("emailStatus", "COMPLAINED");
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account(Set.of(), null)));
        assertThat(census.login("acc-1")).containsEntry("email", "laura@example.test").containsEntry("emailStatus", null);
        assertThat(census.login(null)).isNull();
    }

    /** AUTH-03 (S03 R-03-06b): the same block carries when the account's last access e-mail finally failed. */
    @Test void AUTH_03_theLoginOfD10CarriesTheAccessEmailFailureMark() {
        assertThat(census.login("acc-1")).containsEntry("accessEmailFailedAt", null);
        when(accounts.accessEmailFailedAt("acc-1")).thenReturn(java.time.Instant.parse("2026-11-13T10:00:00Z"));
        assertThat(census.login("acc-1")).containsEntry("accessEmailFailedAt", java.time.Instant.parse("2026-11-13T10:00:00Z"));
    }

    @Test void AUTH_02_theLinkGoesOutForTheClubsAppHost() {
        when(clubs.appHost()).thenReturn("club-a.example.test");
        census.sendLoginEmailChange("acc-1", "new@example.test", "admin-1");
        verify(links).sendEmailChange("acc-1", "new@example.test", "admin-1", "club-a.example.test");
    }

    // --- the used link ------------------------------------------------------------------------------------------------------

    final AuditWriter audits = mock(AuditWriter.class);
    final AccountEmailChangeService changes = new AccountEmailChangeService(accounts, memberships, audits,
            () -> new AuditActor("link-holder", "Link holder", "MEMBER", null, null, "198.51.100.9", "Example browser", "trace-1"),
            Clock.fixed(NOW, ZoneOffset.UTC));

    static MagicLinkToken link(MagicLinkToken.Purpose purpose, String email) {
        return new MagicLinkToken("token-1", "hash", "acc-1", "club-a", "clubs-app", purpose, null, NOW, NOW.plusSeconds(3600), null, null, null,
                email, "admin-1");
    }

    @Test void AUTH_02_onlyAChangeLinkWhoseAddressIsStillFreeApplies() {
        when(accounts.findByEmail("new@example.test")).thenReturn(Optional.empty());
        assertThat(changes.applicable(link(MagicLinkToken.Purpose.EMAIL_CHANGE, "new@example.test"))).isTrue();
        assertThat(changes.applicable(link(MagicLinkToken.Purpose.LOGIN, "new@example.test"))).isFalse();
        assertThat(changes.applicable(link(MagicLinkToken.Purpose.EMAIL_CHANGE, null))).isFalse();
        var other = new Account("acc-2", "new@example.test", "Other", "ca", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, NOW);
        when(accounts.findByEmail("new@example.test")).thenReturn(Optional.of(other));
        assertThat(changes.applicable(link(MagicLinkToken.Purpose.EMAIL_CHANGE, "new@example.test"))).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test void AUTH_02_theUsedLinkChangesTheLoginAndIsAuditedAsTheRequestingAdmins() {
        when(accounts.changeEmail("acc-1", "new@example.test", NOW)).thenReturn(true);
        when(accounts.findById("admin-1")).thenReturn(Optional.of(new Account("admin-1", "admin@example.test", "Alex Admin", "ca", null,
                Set.of(), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(), false, NOW)));

        changes.apply(link(MagicLinkToken.Purpose.EMAIL_CHANGE, "new@example.test"));

        verify(accounts).changeEmail("acc-1", "new@example.test", NOW);
        var command = ArgumentCaptor.forClass(AuditCommand.class);
        var actor = ArgumentCaptor.forClass(AuditActor.class);
        verify(audits).writeAs(command.capture(), actor.capture(), eq("BACKOFFICE"));
        assertThat(command.getValue().action()).isEqualTo(AuditAction.ACCOUNT_EMAIL_CHANGED);
        assertThat(command.getValue().entityType()).isEqualTo("Account");
        assertThat(command.getValue().entityId()).isEqualTo("acc-1");
        assertThat(command.getValue().memberId()).isEqualTo("member-1");
        assertThat((Map<String, Object>) command.getValue().before()).containsEntry("email", "laura@example.test")
                .containsEntry("emailStatus", NotificationAccounts.EmailStatus.COMPLAINED);
        assertThat((Map<String, Object>) command.getValue().after()).containsEntry("email", "new@example.test").containsEntry("emailStatus", null);
        // The ADMIN's entry: never the link holder's identity, address or browser.
        assertThat(actor.getValue()).isEqualTo(new AuditActor("admin-1", "Alex Admin", "ADMIN", null, null, null, null, "trace-1"));
    }

    @Test void AUTH_02_anAccountThatIsNoLongerActiveIsNotChanged() {
        when(accounts.changeEmail(any(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> changes.apply(link(MagicLinkToken.Purpose.EMAIL_CHANGE, "new@example.test"))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(audits);
    }
}
