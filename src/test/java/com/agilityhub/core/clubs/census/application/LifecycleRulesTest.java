package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.clubs.census.persistence.inactivity.InactivityPeriod;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** S13 T-13-05/06/07 run without a Spring context or Mongo. */
class LifecycleRulesTest {
    InactivityPeriod period(InactivityState state, String from, String to, long first) {
        return new InactivityPeriod("p", "club", "m", from, to, null, state, LifecycleOrigin.APP, Instant.EPOCH, null, null,
                new InactivityPeriod.FeeSnapshot(new Money(first, "EUR"), new Money(1000, "EUR")), null, null, null, null, null, null, List.of(), List.of(), 0L, Instant.EPOCH, Instant.EPOCH);
    }
    @Test void T_13_05_onlyApprovedActiveOrFinishedMonthsUseTheFrozenFee() {
        for (var state : InactivityState.values()) {
            var p = period(state, "2026-10", "2026-12", 2000);
            boolean billed = Set.of(InactivityState.APPROVED, InactivityState.ACTIVE, InactivityState.FINISHED).contains(state);
            for (int month : List.of(9, 10, 11, 12)) {
                var fee = InactivityFeeService.fee(p, YearMonth.of(2026, month));
                if (billed && month >= 10) { assertThat(fee).contains(new Money(month == 10 ? 2000 : 1000, "EUR")); }
                else { assertThat(fee).isEmpty(); }
            }
            assertThat(InactivityFeeService.fee(p, YearMonth.of(2027, 1))).isEmpty();
        }
        var old = period(InactivityState.ACTIVE, "2026-09", null, 2000);
        var closed = period(InactivityState.ACTIVE, "2026-09", "2026-11", 2000);
        assertThat(InactivityFeeService.fee(old, YearMonth.of(2027, 2))).contains(new Money(1000, "EUR"));
        assertThat(InactivityFeeService.fee(closed, YearMonth.of(2026, 12))).isEmpty();
        assertThat(InactivityFeeService.fee(old, YearMonth.of(2026, 9))).contains(new Money(2000, "EUR"));
        assertThat(InactivityFeeService.fee(period(InactivityState.APPROVED, "2027-01", null, 2500), YearMonth.of(2027, 1))).contains(new Money(2500, "EUR"));
    }
    @Test void T_13_06_leaveBillingIncludesTheFinalMonthAccordingToTheParameter() {
        assertThat(LeaveBillingService.lastMonth(null, true)).isEmpty();
        assertThat(LeaveBillingService.lastMonth(null, false)).isEmpty();
        assertThat(LeaveBillingService.lastMonth(LocalDate.parse("2026-11-10"), true)).contains(YearMonth.of(2026, 11));
        assertThat(LeaveBillingService.lastMonth(LocalDate.parse("2026-11-10"), false)).contains(YearMonth.of(2026, 10));
        for (boolean full : List.of(true, false)) { assertThat(LeaveBillingService.lastMonth(LocalDate.parse("2026-10-31"), full)).contains(YearMonth.of(2026, 10)); }
    }
    @Test @SuppressWarnings("unchecked") void T_13_07_eligibilityUsesTheDogsOwnerAndIncludesTheLeaveDate() {
        var members = mock(CensusRepository.class); var dogs = mock(CensusRepository.class); var groups = mock(CensusRepository.class);
        var references = mock(CensusReferences.class); var configs = mock(ClubConfigService.class);
        var access = spy(new CensusAccess(members, dogs, groups, references, configs));
        var owner = new Member(); owner.id = "m"; owner.status = "ACTIVE"; owner.bookingBlock = Map.of("active", false);
        var dog = new Dog(); dog.id = "d"; dog.memberId = "m"; dog.status = "ACTIVE";
        when(members.require("m")).thenReturn(owner); when(dogs.require("d")).thenReturn(dog);
        doReturn(true).when(access).enabled(Module.INACTIVITY);
        when(references.approvedInactivity("m")).thenReturn(List.of(Map.of("from", LocalDate.parse("2026-11-01"), "to", LocalDate.parse("2026-12-31"))));
        var eligibility = new MemberBookingEligibility(access);
        eligibility.check("m", "d", LocalDate.parse("2026-10-30")); eligibility.check("m", "d", LocalDate.parse("2027-01-02"));
        assertThatThrownBy(() -> eligibility.check("m", "d", LocalDate.parse("2026-11-03"))).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INACTIVITY_PERIOD));
        doReturn(false).when(access).enabled(Module.INACTIVITY);
        eligibility.check("m", "d", LocalDate.parse("2026-11-03"));
        owner.leaveDate = LocalDate.parse("2026-10-31"); eligibility.check("m", "d", owner.leaveDate);
        assertThatThrownBy(() -> eligibility.check("m", "d", LocalDate.parse("2026-11-01"))).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.MEMBER_LEAVING));
        doReturn(true).when(access).enabled(Module.INACTIVITY);
        assertThatThrownBy(() -> eligibility.check("m", "d", LocalDate.parse("2026-11-03"))).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INACTIVITY_PERIOD));
    }
}
