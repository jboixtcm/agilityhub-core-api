package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.platform.domain.events.PrivacyEvent;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequestRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.SignupCapabilities;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.MockClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S14 R-14-15 `ErasureExecutor` over the context steps (T-14-08's orchestration, T-14-19, T-14-24). */
class ErasureExecutorTest {
    static final Instant NOW = Instant.parse("2026-09-30T05:00:00Z");
    final ErasureRequestRepository requests = mock(ErasureRequestRepository.class);
    final ErasureDirectory directory = mock(ErasureDirectory.class);
    final ErasureAccounts accounts = mock(ErasureAccounts.class);
    final ErasureCheck checks = mock(ErasureCheck.class);
    final ErasureStep step = mock(ErasureStep.class);
    final AccountErasureStep accountStep = mock(AccountErasureStep.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final EventPublisher events = mock(EventPublisher.class);
    final AuditWriter audit = mock(AuditWriter.class);
    final ErasureFilesCleanup cleanup = mock(ErasureFilesCleanup.class);
    final ErasurePseudonyms pseudonyms = new ErasurePseudonyms(new SignupCapabilities(new MockEnvironment(), new MockClock(NOW)));
    ErasureExecutor executor;
    TenantContext.Scope tenant;

    static ErasureMember montse(Instant erasedAt) {
        return new ErasureMember("montse", "montse-account", 87, "LEFT", LocalDate.of(2026, 8, 31), null, "LEAVE_REQUEST", erasedAt, List.of("kiwi"));
    }
    @BeforeEach void open() throws Exception {
        executor = new ErasureExecutor(requests, directory, accounts, new ErasureEligibility(List.of(checks), accounts), List.of(step), List.of(accountStep),
                pseudonyms, configs, new IcuMessageSource(), events, audit, PrivacyFixtures.transactions(), cleanup, new MockClock(NOW));
        tenant = TenantContext.open("club-a");
        when(configs.get(anyString())).thenAnswer(call -> PrivacyFixtures.config(call.getArgument(0)));
        when(checks.blockReasons(any(), any())).thenReturn(EnumSet.noneOf(ErasureBlockReason.class));
        when(requests.update(any(), any())).thenReturn(true);
        when(step.erase(any())).thenReturn(ErasureEffect.builder().file("attachments", "photo-key").count("dogs", 1).build());
        when(accountStep.eraseAccount(any(), any(), any(), any())).thenReturn(ErasureEffect.NONE);
        when(accounts.account("montse-account")).thenReturn(Optional.of(new ErasureAccounts.Account("montse-account", "montse@example.test", "ca", false, false)));
        when(directory.member("montse")).thenReturn(Optional.of(montse(null)));
    }
    @AfterEach void close() { if (tenant != null) { tenant.close(); } }

    @Test void T_14_19_aDueMemberRequestErasesTheClubThenTheAccountAndPublishesAccountErased() {
        when(requests.findById("r")).thenReturn(Optional.of(PrivacyFixtures.request("r", "MEMBER", "SCHEDULED", List.of("montse"), List.of("club-a"), List.of(), "montse-account")));
        when(accounts.memberships("montse-account")).thenReturn(List.of(new ErasureAccounts.Membership("club-a", "montse", "SUSPENDED")));
        assertThat(executor.execute("r")).isEqualTo(ErasureExecutor.Outcome.ERASED);
        var subject = ArgumentCaptor.forClass(ErasureSubject.class);
        verify(step).erase(subject.capture());
        assertThat(subject.getValue().memberIds()).containsExactly("montse");
        assertThat(subject.getValue().dogIds()).containsExactly("kiwi");
        assertThat(subject.getValue().erasedAccountIds()).containsExactly("montse-account");
        assertThat(subject.getValue().pseudonyms().get("montse-account")).isEqualTo(pseudonyms.pseudonym("montse@example.test")).startsWith("erased+");
        assertThat(subject.getValue().placeholders()).isEqualTo(new ErasurePlaceholders("Abonat suprimit", "Compte suprimit", "Gos suprimit", "(suprimit)"));
        assertThat(subject.getValue().memberLabel("montse")).isEqualTo("Abonat suprimit #87");
        verify(accountStep).eraseAccount("montse-account", pseudonyms.pseudonym("montse@example.test"), subject.getValue().placeholders(), NOW);
        verify(audit).writeAsSystem(argThat(command -> command.action() == AuditAction.MEMBER_ERASED && "montse".equals(command.entityId())), eq("erasures"));
        verify(audit).writeAsSystem(argThat(command -> command.action() == AuditAction.ACCOUNT_ERASED && "montse-account".equals(command.entityId())), eq("erasures"));
        var update = ArgumentCaptor.forClass(Update.class);
        verify(requests).update(any(), update.capture());
        assertThat(update.getValue().getUpdateObject().toString()).contains("ERASING", "attachments:photo-key", "erasedClubIds", "accountErased=true");
        var event = ArgumentCaptor.forClass(PrivacyEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo("AccountErased");
        assertThat(event.getValue().payload()).containsEntry("accountId", "montse-account").containsEntry("requestId", "r")
                .containsEntry("memberIds", List.of("montse")).containsEntry("clubIds", List.of("club-a"));
    }

    @Test void T_14_19_anAccountWithAnotherMembershipOrLinkedToLearnIsKept() {
        when(requests.findById("r")).thenReturn(Optional.of(PrivacyFixtures.request("r", "MEMBER", "SCHEDULED", List.of("montse"), List.of("club-a"), List.of(), "montse-account")));
        when(accounts.memberships("montse-account")).thenReturn(List.of(new ErasureAccounts.Membership("club-a", "montse", "SUSPENDED"),
                new ErasureAccounts.Membership("club-b", "montse-b", "ACTIVE")));
        assertThat(executor.execute("r")).isEqualTo(ErasureExecutor.Outcome.ERASED);
        verify(step).erase(argThat(subject -> subject.erasedAccountIds().isEmpty() && subject.accountIds().contains("montse-account")));
        verifyNoInteractions(accountStep);
        verify(events).publish(argThat(event -> !event.payload().containsKey("accountId")));
        reset(step, events); when(step.erase(any())).thenReturn(ErasureEffect.NONE);
        when(accounts.memberships("montse-account")).thenReturn(List.of(new ErasureAccounts.Membership("club-a", "montse", "SUSPENDED")));
        when(accounts.account("montse-account")).thenReturn(Optional.of(new ErasureAccounts.Account("montse-account", "montse@example.test", "ca", true, false)));
        assertThat(executor.execute("r")).isEqualTo(ErasureExecutor.Outcome.ERASED);
        verify(step).erase(argThat(subject -> subject.erasedAccountIds().isEmpty()));
        verifyNoInteractions(accountStep);
    }

    /**
     * S14 R-14-15 exception (E11-T01 round 2): the account stays while another club's membership is not `ERASED` — a `SUSPENDED`
     * one too (a former member of that club), so that club's member never points at an erased account.
     */
    @Test void T_14_19_aSuspendedMembershipElsewhereAlsoKeepsTheAccount() {
        when(requests.findById("r")).thenReturn(Optional.of(PrivacyFixtures.request("r", "MEMBER", "SCHEDULED", List.of("montse"), List.of("club-a"), List.of(), "montse-account")));
        when(accounts.memberships("montse-account")).thenReturn(List.of(new ErasureAccounts.Membership("club-a", "montse", "SUSPENDED"),
                new ErasureAccounts.Membership("club-b", "montse-b", "SUSPENDED")));
        assertThat(executor.execute("r")).isEqualTo(ErasureExecutor.Outcome.ERASED);
        verify(step).erase(argThat(subject -> subject.erasedAccountIds().isEmpty()));
        verifyNoInteractions(accountStep);
        // Once that membership is erased too, nothing holds the account any more.
        reset(step); when(step.erase(any())).thenReturn(ErasureEffect.NONE);
        when(accounts.memberships("montse-account")).thenReturn(List.of(new ErasureAccounts.Membership("club-a", "montse", "SUSPENDED"),
                new ErasureAccounts.Membership("club-b", "montse-b", "ERASED")));
        assertThat(executor.execute("r")).isEqualTo(ErasureExecutor.Outcome.ERASED);
        verify(step).erase(argThat(subject -> subject.erasedAccountIds().contains("montse-account")));
    }

    @Test void R_14_15_aFailingCheckBlocksTheRequestWithItsReasonsAndErasesNothing() {
        when(requests.findById("r")).thenReturn(Optional.of(PrivacyFixtures.request("r", "MEMBER", "SCHEDULED", List.of("montse"), List.of("club-a"), List.of(), "montse-account")));
        when(checks.blockReasons(any(), any())).thenReturn(EnumSet.of(ErasureBlockReason.INVOICES_OPEN));
        assertThat(executor.execute("r")).isEqualTo(ErasureExecutor.Outcome.BLOCKED);
        var update = ArgumentCaptor.forClass(Update.class);
        verify(requests).update(any(), update.capture());
        assertThat(update.getValue().getUpdateObject().toString()).contains("BLOCKED", "INVOICES_OPEN");
        verifyNoInteractions(step, accountStep, events);
        verify(audit, never()).writeAsSystem(any(), any());
    }

    @Test void T_14_24_aRequestNotDueHereOrAlreadyMovedIsSkippedAndALostCompareAndSetRollsBack() {
        when(requests.findById("gone")).thenReturn(Optional.empty());
        assertThat(executor.execute("gone")).isEqualTo(ErasureExecutor.Outcome.SKIPPED);
        when(requests.findById("erasing")).thenReturn(Optional.of(PrivacyFixtures.request("erasing", "MEMBER", "ERASING", List.of("montse"), List.of("club-a"), List.of(), null)));
        assertThat(executor.execute("erasing")).isEqualTo(ErasureExecutor.Outcome.SKIPPED);
        when(requests.findById("other")).thenReturn(Optional.of(PrivacyFixtures.request("other", "MEMBER", "SCHEDULED", List.of("montse"), List.of("club-b"), List.of(), null)));
        assertThat(executor.execute("other")).isEqualTo(ErasureExecutor.Outcome.SKIPPED);
        when(requests.findById("done")).thenReturn(Optional.of(PrivacyFixtures.request("done", "ACCOUNT", "SCHEDULED", List.of("montse"), List.of("club-a", "club-b"), List.of("club-a"), "montse-account")));
        assertThat(executor.execute("done")).isEqualTo(ErasureExecutor.Outcome.SKIPPED);
        verifyNoInteractions(step);
        when(requests.findById("r")).thenReturn(Optional.of(PrivacyFixtures.request("r", "MEMBER", "SCHEDULED", List.of("montse"), List.of("club-a"), List.of(), null)));
        when(requests.update(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> executor.execute("r")).hasMessage("STALE_VERSION");
    }

    @Test void R_14_15_anAccountRequestErasesClubByClubAndTheLastClubErasesTheAccount() {
        when(requests.findById("r")).thenReturn(Optional.of(PrivacyFixtures.request("r", "ACCOUNT", "SCHEDULED", List.of("montse", "montse-b"),
                List.of("club-a", "club-b"), List.of(), "montse-account")));
        when(accounts.memberships("montse-account")).thenReturn(List.of(new ErasureAccounts.Membership("club-a", "montse", "SUSPENDED")));
        assertThat(executor.execute("r")).isEqualTo(ErasureExecutor.Outcome.PARTIAL);
        verify(step).erase(argThat(subject -> subject.erasedAccountIds().isEmpty()));
        verifyNoInteractions(accountStep, events);
        var update = ArgumentCaptor.forClass(Update.class);
        verify(requests).update(any(), update.capture());
        assertThat(update.getValue().getUpdateObject().toString()).contains("SCHEDULED", "erasedClubIds", "attachments:photo-key").doesNotContain("ERASING");
        // E11-T11: club A's files leave the store once its part commits, not when the request completes.
        verify(cleanup).cleanAfterCommit("r");
        // The other club completes it: the account goes.
        tenant.close(); tenant = TenantContext.open("club-b"); reset(requests); when(requests.update(any(), any())).thenReturn(true);
        when(directory.member("montse")).thenReturn(Optional.empty());
        when(directory.member("montse-b")).thenReturn(Optional.of(new ErasureMember("montse-b", "montse-account", 12, "LEFT", LocalDate.of(2026, 8, 1), null, null, null, List.of())));
        when(requests.findById("r")).thenReturn(Optional.of(PrivacyFixtures.request("r", "ACCOUNT", "SCHEDULED", List.of("montse", "montse-b"),
                List.of("club-a", "club-b"), List.of("club-a"), "montse-account")));
        assertThat(executor.execute("r")).isEqualTo(ErasureExecutor.Outcome.ERASED);
        verify(accountStep).eraseAccount(eq("montse-account"), anyString(), any(), eq(NOW));
        verify(events).publish(argThat(event -> "montse-account".equals(event.payload().get("accountId")) && event.clubId().equals("club-b")));
        // The completing club's files are AccountErased's consumer's (through the outbox).
        verifyNoMoreInteractions(cleanup);
    }

    @Test void R_14_15_anAccountRequestIsBlockedByAnActiveMembership() {
        when(requests.findById("r")).thenReturn(Optional.of(PrivacyFixtures.request("r", "ACCOUNT", "BLOCKED", List.of("montse"), List.of("club-a"), List.of(), "montse-account")));
        when(accounts.memberships("montse-account")).thenReturn(List.of(new ErasureAccounts.Membership("club-c", null, "ACTIVE")));
        assertThat(executor.execute("r")).isEqualTo(ErasureExecutor.Outcome.BLOCKED);
        verifyNoInteractions(step);
    }

    @Test void T_14_08_anAlreadyErasedMemberIsNotAuditedAgain() {
        when(directory.member("montse")).thenReturn(Optional.of(montse(Instant.parse("2026-09-01T00:00:00Z"))));
        when(requests.findById("r")).thenReturn(Optional.of(PrivacyFixtures.request("r", "MEMBER", "BLOCKED", List.of("montse"), List.of("club-a"), List.of(), null)));
        assertThat(executor.execute("r")).isEqualTo(ErasureExecutor.Outcome.ERASED);
        verify(audit, never()).writeAsSystem(argThat(command -> command.action() == AuditAction.MEMBER_ERASED), any());
    }

    @Test void R_14_14_anAccountWithoutMembersIsErasedOutsideAnyClub() {
        tenant.close(); tenant = null;
        var request = PrivacyFixtures.request("r", "ACCOUNT", "SCHEDULED", List.of(), List.of(), List.of(), "montse-account");
        when(requests.findById("r")).thenReturn(Optional.of(request));
        executor.executeAccountOnly("r");
        verify(accountStep).eraseAccount(eq("montse-account"), eq(pseudonyms.pseudonym("montse@example.test")),
                eq(new ErasurePlaceholders("Abonat suprimit", "Compte suprimit", "Gos suprimit", "(suprimit)")), eq(NOW));
        verify(events).publish(argThat(event -> event.clubId() == null && "AccountErased".equals(event.type())));
        reset(accountStep, events);
        when(requests.findById("r")).thenReturn(Optional.of(PrivacyFixtures.request("r", "ACCOUNT", "ERASING", List.of(), List.of(), List.of(), "montse-account")));
        executor.executeAccountOnly("r");
        verifyNoInteractions(accountStep, events);
        when(requests.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> executor.executeAccountOnly("missing")).hasMessage("NOT_FOUND");
    }
}
