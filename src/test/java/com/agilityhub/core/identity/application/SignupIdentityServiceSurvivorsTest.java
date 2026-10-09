package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.IdentityEvent;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.shared.application.EventPublisher;
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
 * E11-T06 PIT survivors of {@link SignupIdentityService} (S04 R-04-06 d, R-04-22): a validation creates the account and membership
 * of a new member, matches an existing account by email, keeps the account of a readmitted member, refuses lost creation races and
 * second memberships, and records the club consent only when the account has not accepted that version yet.
 * Collaborators are mocks; fictional data.
 */
class SignupIdentityServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant ACCEPTED = NOW.minus(Duration.ofHours(20));
    static final Instant CREATED = Instant.parse("2025-03-01T10:00:00Z");

    final AccountRepository accounts = mock(AccountRepository.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final EventPublisher events = mock(EventPublisher.class);
    final SignupIdentityService service = new SignupIdentityService(accounts, memberships, events, Clock.fixed(NOW, ZoneOffset.UTC));
    TenantContext.Scope tenant;

    @BeforeEach void setUp() { TenantContext.clear(); tenant = TenantContext.open("club-a"); }

    @AfterEach void clearTenant() { tenant.close(); TenantContext.clear(); }

    /** Accounts are only ever ACTIVE (AccountService.java:37, SignupIdentityService.java:26; no writer changes `status`). */
    static Account account(String id, String email, List<Account.Consent> consents) {
        return new Account(id, email, "Laura Example", "ca", null, Set.of(), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(),
                false, CREATED, null, CREATED, Account.Source.SIGNUP, null, null, consents, List.of());
    }

    /**
     * The membership of a member who left: RoleAssignmentService.memberLeft (:192) saves it without roles, so TeamMembershipService
     * suspends it and drops the default profile (TeamMembershipService.java:71-76).
     */
    static Membership formerMember() {
        return new Membership("mem-1", "acc-1", "club-a", "member-1", Set.of(), Membership.Status.SUSPENDED, null, false, null, CREATED,
                CREATED.plus(Duration.ofDays(30)), null, 5, CREATED.plus(Duration.ofDays(200)), null, null);
    }

    /** An active membership as SignupIdentityService.java:39 writes it. */
    static Membership activeMember(String memberId) {
        return new Membership("mem-9", "acc-1", "club-a", memberId, Set.of(Role.MEMBER), Membership.Status.ACTIVE, Role.MEMBER, false, null,
                CREATED, CREATED.plus(Duration.ofDays(30)));
    }

    private List<IdentityEvent> published() {
        var captor = ArgumentCaptor.forClass(IdentityEvent.class);
        verify(events, atLeastOnce()).publish(captor.capture());
        return captor.getAllValues();
    }

    @Test void E11_T06_aNewMemberGetsANewAccountWithTheClubConsentAndAMemberMembership() {
        when(accounts.createIfAbsent(any())).thenReturn(true);

        String id = service.validate("member-1", null, "laura@example.test", "Laura Example", "ca", "v3", ACCEPTED, false);

        var created = ArgumentCaptor.forClass(Account.class);
        verify(accounts).createIfAbsent(created.capture());
        assertThat(id).isNotBlank().isEqualTo(created.getValue().id());
        assertThat(created.getValue().createdSource()).isEqualTo(Account.Source.SIGNUP);
        assertThat(created.getValue().consents()).containsExactly(new Account.Consent(Account.ConsentPolicy.CLUB, "club-a", "v3", ACCEPTED));
        var inserted = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).insert(inserted.capture());
        assertThat(inserted.getValue().accountId()).isEqualTo(id);
        assertThat(inserted.getValue().memberId()).isEqualTo("member-1");
        assertThat(inserted.getValue().roles()).containsExactly(Role.MEMBER);
        verify(accounts, never()).findById(any());
        verify(accounts, never()).completeOnboarding(any(), any());
        var membershipChanged = published().stream().filter(event -> event.kind() == IdentityEvent.Kind.MembershipChanged).findFirst().orElseThrow();
        assertThat(membershipChanged.payload()).containsEntry("before", List.of()).containsEntry("after", List.of("MEMBER"));
    }

    @Test void E11_T06_aLostAccountCreationRaceIsAnExistingMembership() {
        when(accounts.createIfAbsent(any())).thenReturn(false);

        assertThatThrownBy(() -> service.validate("member-1", null, "laura@example.test", "Laura Example", "ca", "v3", ACCEPTED, false))
                .isInstanceOf(ApiException.class).hasMessage("MEMBERSHIP_EXISTS");
        verify(memberships, never()).insert(any());
    }

    @Test void E11_T06_aMemberWithoutAccountIsMatchedToTheExistingAccountOfItsEmail() {
        // An AgilityHub account from another club that never accepted this club's policy.
        when(accounts.findByEmail("laura@example.test")).thenReturn(Optional.of(account("acc-1", "laura@example.test",
                List.of(new Account.Consent(Account.ConsentPolicy.CLUB, "club-b", "v1", CREATED)))));

        assertThat(service.validate("member-1", null, "laura@example.test", "Laura Example", "ca", "v3", ACCEPTED, false)).isEqualTo("acc-1");

        verify(accounts, never()).createIfAbsent(any());
        verify(memberships).insert(any());
        verify(accounts).completeOnboarding("acc-1", new Account.Consent(Account.ConsentPolicy.CLUB, "club-a", "v3", ACCEPTED));
    }

    @Test void E11_T06_aReadmittedMemberKeepsItsAccountAndReactivatesItsMembership() {
        // R-04-22: the account's login email is not the signup's email, and is never changed.
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account("acc-1", "laura.old@example.test",
                List.of(new Account.Consent(Account.ConsentPolicy.CLUB, "club-a", "v2", CREATED)))));
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(formerMember()));

        assertThat(service.validate("member-1", "acc-1", "laura@example.test", "Laura Example", "ca", "v3", ACCEPTED, true)).isEqualTo("acc-1");

        verify(accounts, never()).findByEmail(any());
        verify(accounts, never()).createIfAbsent(any());
        var saved = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).saveTeam(saved.capture());
        assertThat(saved.getValue()).isEqualTo(new Membership("mem-1", "acc-1", "club-a", "member-1", Set.of(Role.MEMBER), Membership.Status.ACTIVE,
                Role.MEMBER, false, null, CREATED, CREATED.plus(Duration.ofDays(30)), null, 6, NOW, null, null));
        verify(memberships, never()).insert(any());
        verify(accounts).completeOnboarding("acc-1", new Account.Consent(Account.ConsentPolicy.CLUB, "club-a", "v3", ACCEPTED));
    }

    @Test void E11_T06_aReadmittedMemberWhoAlreadyAcceptedThisVersionIsNotAskedAgain() {
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account("acc-1", "laura@example.test",
                List.of(new Account.Consent(Account.ConsentPolicy.CLUB, "club-a", "v3", CREATED.plus(Duration.ofDays(100)))))));
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(formerMember()));

        service.validate("member-1", "acc-1", "laura@example.test", "Laura Example", "ca", "v3", ACCEPTED, true);

        verify(accounts, never()).completeOnboarding(any(), any());
    }

    @Test void E11_T06_aNewMemberWhoseEmailAccountAlreadyHasAMembershipIsAnExistingMembership() {
        // A new (non-readmission) member has no account yet (SignupService.java:1005 passes `member.accountId`), and its primary
        // email is the login of an account that already belongs to another member of this club.
        when(accounts.findByEmail("laura@example.test")).thenReturn(Optional.of(account("acc-1", "laura@example.test", List.of())));
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(activeMember("member-9")));

        assertThatThrownBy(() -> service.validate("member-1", null, "laura@example.test", "Laura Example", "ca", "v3", ACCEPTED, false))
                .isInstanceOf(ApiException.class).hasMessage("MEMBERSHIP_EXISTS");
        verify(memberships, never()).saveTeam(any());
        verify(memberships, never()).insert(any());
    }
}
