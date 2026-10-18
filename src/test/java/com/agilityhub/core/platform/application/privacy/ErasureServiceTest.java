package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.platform.domain.events.PrivacyEvent;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequest;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequestRepository;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.MockClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.query.Update;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S14 R-14-14: requests and cancellations (T-14-09, T-14-18). */
class ErasureServiceTest {
    static final Instant NOW = Instant.parse("2026-09-05T08:00:00Z");
    final ErasureRequestRepository requests = mock(ErasureRequestRepository.class);
    final ErasureDirectory directory = mock(ErasureDirectory.class);
    final ErasureAccounts accounts = mock(ErasureAccounts.class);
    final ErasureCheck checks = mock(ErasureCheck.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final EventPublisher events = mock(EventPublisher.class);
    final AuditWriter audit = mock(AuditWriter.class);
    final ErasureExecutor executor = mock(ErasureExecutor.class);
    final ErasureFilesCleanup cleanup = mock(ErasureFilesCleanup.class);
    final ErasureService service = new ErasureService(requests, directory, accounts, new ErasureEligibility(List.of(checks), accounts), configs, events, audit,
            PrivacyFixtures.transactions(), executor, cleanup, new MockClock(NOW));
    TenantContext.Scope tenant; CurrentUser.Scope user;

    static ErasureMember montse(String status, Instant erasedAt) {
        return new ErasureMember("montse", "montse-account", 87, status, LocalDate.of(2026, 8, 31), null, "LEAVE_REQUEST", erasedAt, List.of("kiwi"));
    }
    @BeforeEach void open() {
        tenant = TenantContext.open("club-a");
        user = CurrentUser.open(new CurrentUser("admin-account", "Example Admin", null, DomainEvent.Origin.BACKOFFICE));
        when(configs.get(anyString())).thenAnswer(call -> PrivacyFixtures.config(call.getArgument(0)));
        when(checks.blockReasons(any(), any())).thenReturn(EnumSet.noneOf(ErasureBlockReason.class));
        when(requests.insert(any())).thenAnswer(call -> call.getArgument(0));
    }
    @AfterEach void close() { user.close(); tenant.close(); }

    @Test void T_14_18_aLeftMemberIsScheduledAtItsMarginWithEventAndAudit() {
        when(directory.member("montse")).thenReturn(Optional.of(montse("LEFT", null)));
        var request = service.requestMember("montse", "a request by e-mail");
        assertThat(request.status()).isEqualTo("SCHEDULED");
        assertThat(request.live()).isTrue();
        assertThat(request.scope()).isEqualTo("MEMBER");
        assertThat(request.source()).isEqualTo("ADMIN");
        assertThat(request.clubId()).isEqualTo("club-a");
        assertThat(request.memberIds()).containsExactly("montse");
        assertThat(request.accountId()).isEqualTo("montse-account");
        assertThat(request.requestedByAccountId()).isEqualTo("admin-account");
        assertThat(request.executeAt()).isEqualTo(Instant.parse("2026-09-29T22:00:00Z"));
        var event = ArgumentCaptor.forClass(PrivacyEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo("AccountErasureRequested");
        assertThat(event.getValue().clubId()).isEqualTo("club-a");
        assertThat(event.getValue().payload()).containsEntry("accountId", "montse-account").containsEntry("source", "ADMIN")
                .containsEntry("scope", "MEMBER").containsEntry("executeAt", "2026-09-29T22:00:00Z").containsEntry("memberIds", List.of("montse"))
                .containsEntry("clubIds", List.of("club-a"));
        var command = ArgumentCaptor.forClass(AuditCommand.class);
        verify(audit).write(command.capture());
        assertThat(command.getValue().action()).isEqualTo(AuditAction.ERASURE_REQUESTED);
        assertThat(command.getValue().memberId()).isEqualTo("montse");
        assertThat(command.getValue().reason()).isEqualTo("a request by e-mail");
    }

    @Test void T_14_18_anyBlockingReasonRefusesAndCreatesNothing() {
        when(directory.member("montse")).thenReturn(Optional.of(montse("LEFT", null)));
        when(checks.blockReasons(any(), any())).thenReturn(EnumSet.of(ErasureBlockReason.INVOICES_OPEN));
        assertThatThrownBy(() -> service.requestMember("montse", null)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.ERASURE_BLOCKED);
            assertThat(error.details()).containsEntry("reasons", List.of("INVOICES_OPEN"));
        });
        when(directory.member("active")).thenReturn(Optional.of(new ErasureMember("active", null, 2, "ACTIVE", null, null, null, null, List.of())));
        assertThatThrownBy(() -> service.requestMember("active", null)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.details()).containsEntry("reasons", List.of("INVOICES_OPEN", "MEMBER_NOT_LEFT")));
        verify(requests, never()).insert(any()); verifyNoInteractions(events, audit);
    }

    @Test void T_14_18_unknownErasedAndAlreadyRequestedMembersAreRefused() {
        when(directory.member("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requestMember("ghost", null)).hasMessage("NOT_FOUND");
        when(directory.member("montse")).thenReturn(Optional.of(montse("LEFT", NOW)));
        assertThatThrownBy(() -> service.requestMember("montse", null)).hasMessage("MEMBER_ERASED");
        when(directory.member("montse")).thenReturn(Optional.of(montse("LEFT", null)));
        when(requests.live("montse")).thenReturn(Optional.of(PrivacyFixtures.request("r", "MEMBER", "SCHEDULED", List.of("montse"), List.of("club-a"), List.of(), null)));
        assertThatThrownBy(() -> service.requestMember("montse", null)).hasMessage("ERASURE_ALREADY_REQUESTED");
        verify(requests, never()).insert(any());
    }

    @Test void T_14_09_aRetentionRequestExecutesNowAndIsAuditedAsTheSystem() {
        var request = service.requestRetention(montse("LEFT", null), "retention-sweep");
        assertThat(request.source()).isEqualTo("RETENTION");
        assertThat(request.executeAt()).isEqualTo(NOW);
        assertThat(request.requestedByAccountId()).isNull();
        verify(audit).writeAsSystem(argThat(command -> command.action() == AuditAction.ERASURE_REQUESTED && "montse".equals(command.memberId())), eq("retention-sweep"));
        verify(events).publish(argThat(event -> "RETENTION".equals(event.payload().get("source"))));
    }

    @Test void T_14_18_theLiveRequestIsReadInItsClubAndCancelledOnlyBeforeErasing() {
        when(directory.member("montse")).thenReturn(Optional.of(montse("LEFT", null)));
        var scheduled = PrivacyFixtures.request("r", "MEMBER", "SCHEDULED", List.of("montse"), List.of("club-a"), List.of(), "montse-account");
        when(requests.live("montse")).thenReturn(Optional.of(scheduled));
        assertThat(service.live("montse")).isSameAs(scheduled);
        when(requests.update(eq(scheduled), any(Update.class))).thenReturn(true);
        service.cancel("montse");
        var update = ArgumentCaptor.forClass(Update.class);
        verify(requests).update(eq(scheduled), update.capture());
        assertThat(update.getValue().getUpdateObject().toString()).contains("CANCELLED", "cancelledByAccountId", "admin-account", "live");
        verify(audit).write(argThat((AuditCommand command) -> command.action() == AuditAction.ERASURE_CANCELLED && "r".equals(command.entityId())));
        // Another club's request is not this club's (404); ERASING and an account's request are 409.
        when(requests.live("montse")).thenReturn(Optional.of(PrivacyFixtures.request("r", "MEMBER", "SCHEDULED", List.of("montse"), List.of("club-b"), List.of(), null)));
        assertThatThrownBy(() -> service.live("montse")).hasMessage("NOT_FOUND");
        when(requests.live("montse")).thenReturn(Optional.of(PrivacyFixtures.request("r", "MEMBER", "ERASING", List.of("montse"), List.of("club-a"), List.of(), null)));
        assertThatThrownBy(() -> service.cancel("montse")).hasMessage("INVALID_STATE");
        when(requests.live("montse")).thenReturn(Optional.of(PrivacyFixtures.request("r", "ACCOUNT", "SCHEDULED", List.of("montse"), List.of("club-a"), List.of(), "a")));
        assertThatThrownBy(() -> service.cancel("montse")).hasMessage("INVALID_STATE");
        when(requests.live("montse")).thenReturn(Optional.of(scheduled));
        when(requests.update(eq(scheduled), any(Update.class))).thenReturn(false);
        assertThatThrownBy(() -> service.cancel("montse")).hasMessage("STALE_VERSION");
    }

    @Test void R_14_14_anAccountRequestChecksEveryClubAndTakesTheLatestMargin() {
        tenant.close(); tenant = null;
        try {
            when(accounts.account("laura-account")).thenReturn(Optional.of(new ErasureAccounts.Account("laura-account", "laura@example.test", "ca", false, false)));
            when(accounts.memberships("laura-account")).thenReturn(List.of(new ErasureAccounts.Membership("club-a", "laura-a", "SUSPENDED"),
                    new ErasureAccounts.Membership("club-b", "laura-b", "SUSPENDED")));
            when(directory.ofAccount("laura-account")).thenAnswer(call -> List.of(new ErasureMember("laura-" + TenantContext.require().substring(5), "laura-account", 3,
                    "LEFT", TenantContext.require().equals("club-a") ? LocalDate.of(2026, 8, 31) : LocalDate.of(2026, 9, 3), null, null, null, List.of())));
            var request = service.requestAccount("laura-account", null);
            assertThat(request.scope()).isEqualTo("ACCOUNT");
            assertThat(request.clubId()).isNull();
            assertThat(request.source()).isEqualTo("PLATFORM");
            assertThat(request.memberIds()).containsExactly("laura-a", "laura-b");
            assertThat(request.clubIds()).containsExactly("club-a", "club-b");
            assertThat(request.executeAt()).isEqualTo(Instant.parse("2026-10-02T22:00:00Z"));
            verify(events).publish(argThat(event -> event.clubId() == null && "ACCOUNT".equals(event.payload().get("scope"))));
            verifyNoInteractions(executor);
            // An active membership anywhere blocks it.
            when(accounts.memberships("laura-account")).thenReturn(List.of(new ErasureAccounts.Membership("club-a", "laura-a", "ACTIVE")));
            assertThatThrownBy(() -> service.requestAccount("laura-account", null)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.details()).containsEntry("reasons", List.of("OTHER_MEMBERSHIPS_ACTIVE")));
        } finally { tenant = TenantContext.open("club-a"); }
    }

    /** S14 §5 «plataforma» (E11-T01 round 2): the console cancels an account request before it erases, never a club's member request. */
    @Test void R_14_14_thePlatformCancelsAScheduledOrBlockedAccountRequestOnly() {
        tenant.close(); tenant = null;
        try {
            var blocked = PrivacyFixtures.request("r", "ACCOUNT", "BLOCKED", List.of("montse", "montse-b"), List.of("club-a", "club-b"), List.of("club-a"), "montse-account");
            when(requests.findById("r")).thenReturn(Optional.of(blocked));
            when(requests.update(eq(blocked), any(Update.class))).thenReturn(true);
            service.cancelAccountRequest("r");
            var update = ArgumentCaptor.forClass(Update.class);
            verify(requests).update(eq(blocked), update.capture());
            assertThat(update.getValue().getUpdateObject().toString()).contains("CANCELLED", "cancelledAt", "cancelledByAccountId", "admin-account", "live")
                    .doesNotContain("erasedClubIds");
            verify(audit).write(argThat((AuditCommand command) -> command.action() == AuditAction.ERASURE_CANCELLED && "r".equals(command.entityId())
                    && command.memberId() == null));
            verifyNoInteractions(cleanup);
            // E11-T11: files the erased club still has pending leave the store after the cancel commits.
            var withFiles = new ErasureRequest("p", null, "ACCOUNT", "montse-account", List.of("montse", "montse-b"), List.of("club-a", "club-b"), "PLATFORM",
                    "platform-account", NOW, null, "BLOCKED", NOW, List.of("INVOICES_OPEN"), null, null, null, List.of("attachments:photo-key"), List.of("club-a"),
                    false, true, null, 5);
            when(requests.findById("p")).thenReturn(Optional.of(withFiles));
            when(requests.update(eq(withFiles), any(Update.class))).thenReturn(true);
            service.cancelAccountRequest("p");
            verify(cleanup).cleanAfterCommit("p");
            for (var refused : List.of(PrivacyFixtures.request("m", "MEMBER", "SCHEDULED", List.of("montse"), List.of("club-a"), List.of(), null),
                    PrivacyFixtures.request("m", "ACCOUNT", "ERASING", List.of("montse"), List.of("club-a"), List.of("club-a"), "montse-account"),
                    PrivacyFixtures.request("m", "ACCOUNT", "CANCELLED", List.of("montse"), List.of("club-a"), List.of(), "montse-account"))) {
                when(requests.findById("m")).thenReturn(Optional.of(refused));
                assertThatThrownBy(() -> service.cancelAccountRequest("m")).isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.INVALID_STATE);
                    assertThat(error.details()).containsEntry("status", refused.status()).containsEntry("scope", refused.scope());
                });
            }
            when(requests.findById("ghost")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.cancelAccountRequest("ghost")).hasMessage("NOT_FOUND");
            when(requests.update(eq(blocked), any(Update.class))).thenReturn(false);
            assertThatThrownBy(() -> service.cancelAccountRequest("r")).hasMessage("STALE_VERSION");
            verify(audit, times(2)).write(any());
        } finally { tenant = TenantContext.open("club-a"); }
    }

    @Test void R_14_14_anAccountWithoutMembersIsErasedAtOnceAndUnknownOrErasedOnesAreRefused() {
        tenant.close(); tenant = null;
        try {
            when(accounts.account("staff-account")).thenReturn(Optional.of(new ErasureAccounts.Account("staff-account", "staff@example.test", "ca", false, false)));
            when(accounts.memberships("staff-account")).thenReturn(List.of());
            var erasing = PrivacyFixtures.request("r", "ACCOUNT", "ERASING", List.of(), List.of(), List.of(), "staff-account");
            when(executor.executeAccountOnly(anyString())).thenReturn(erasing);
            assertThat(service.requestAccount("staff-account", "requested")).isSameAs(erasing);
            when(accounts.account("ghost")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.requestAccount("ghost", null)).hasMessage("NOT_FOUND");
            when(accounts.account("gone")).thenReturn(Optional.of(new ErasureAccounts.Account("gone", "erased+x@erased.invalid", null, false, true)));
            assertThatThrownBy(() -> service.requestAccount("gone", null)).hasMessage("ERASURE_ALREADY_REQUESTED");
        } finally { tenant = TenantContext.open("club-a"); }
    }
}
