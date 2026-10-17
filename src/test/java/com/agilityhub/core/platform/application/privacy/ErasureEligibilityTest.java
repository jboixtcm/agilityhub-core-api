package com.agilityhub.core.platform.application.privacy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S14 R-14-14 `ErasureEligibility` (T-14-09). */
class ErasureEligibilityTest {
    static final Instant NOW = Instant.parse("2026-09-05T08:00:00Z");
    static ErasureMember member(String status, Instant erasedAt) {
        return new ErasureMember("member-a", "account-a", 1, status, LocalDate.of(2026, 8, 31), null, null, erasedAt, List.of("dog-a"));
    }
    final ErasureCheck payments = mock(ErasureCheck.class), bookings = mock(ErasureCheck.class);
    final ErasureAccounts accounts = mock(ErasureAccounts.class);
    final ErasureEligibility eligibility = new ErasureEligibility(List.of(payments, bookings), accounts);

    @Test void T_14_09_anActiveMemberIsNotLeftAndEveryContextIsAsked() {
        when(payments.blockReasons(any(), any())).thenReturn(EnumSet.noneOf(ErasureBlockReason.class));
        when(bookings.blockReasons(any(), any())).thenReturn(EnumSet.of(ErasureBlockReason.FUTURE_BOOKINGS));
        assertThat(eligibility.check(List.of(member("ACTIVE", null)), NOW))
                .containsExactlyInAnyOrder(ErasureBlockReason.MEMBER_NOT_LEFT, ErasureBlockReason.FUTURE_BOOKINGS);
        verify(payments).blockReasons(List.of("member-a"), NOW);
    }

    @Test void T_14_09_aLeftMemberWithOpenInvoicesOrUpfrontPaymentsIsBlocked() {
        when(payments.blockReasons(any(), any())).thenReturn(EnumSet.of(ErasureBlockReason.INVOICES_OPEN, ErasureBlockReason.UPFRONT_OPEN));
        when(bookings.blockReasons(any(), any())).thenReturn(EnumSet.noneOf(ErasureBlockReason.class));
        assertThat(eligibility.check(List.of(member("LEFT", null)), NOW)).containsExactlyInAnyOrder(ErasureBlockReason.INVOICES_OPEN, ErasureBlockReason.UPFRONT_OPEN);
    }

    @Test void T_14_09_aCleanLeftMemberPassesAndAnErasedOneIsNotCheckedAgain() {
        when(payments.blockReasons(any(), any())).thenReturn(EnumSet.noneOf(ErasureBlockReason.class));
        when(bookings.blockReasons(any(), any())).thenReturn(EnumSet.noneOf(ErasureBlockReason.class));
        assertThat(eligibility.check(List.of(member("LEFT", null)), NOW)).isEmpty();
        clearInvocations(payments);
        assertThat(eligibility.check(List.of(member("LEFT", NOW)), NOW)).isEmpty();
        verifyNoInteractions(payments);
    }

    @Test void T_14_09_anAccountWithAnActiveMembershipIsBlocked() {
        when(accounts.memberships("account-a")).thenReturn(List.of(new ErasureAccounts.Membership("club-a", "member-a", "SUSPENDED"),
                new ErasureAccounts.Membership("club-b", null, "ACTIVE")));
        assertThat(eligibility.otherMembershipsActive("account-a")).isTrue();
        when(accounts.memberships("account-b")).thenReturn(List.of(new ErasureAccounts.Membership("club-a", "member-b", "ERASED")));
        assertThat(eligibility.otherMembershipsActive("account-b")).isFalse();
        assertThat(eligibility.otherMembershipsActive(null)).isFalse();
    }
}
