package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.catalogs.application.PlanService;
import com.agilityhub.core.clubs.catalogs.application.PriceResolver;
import com.agilityhub.core.clubs.census.application.ActivityMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.payments.application.PackBalanceService;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.DemoSeedActor;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link DemoMembers} (E4-T05/E5-T06 demo actors): the candidate pool in member-number order with
 * only ACTIVE dogs, the missing-dog and missing-pack-plan failures, the pack a non-pack member never gets, and the waiting
 * entry `join` hands back.
 */
class DemoMembersSurvivorsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant STARTS = Instant.parse("2026-10-12T16:00:00Z");
    static final Instant AT = Instant.parse("2026-10-11T18:00:01Z");
    static final LocalDate CLASS_DATE = LocalDate.parse("2026-10-12");

    final ActivityMemberAccess census = mock(ActivityMemberAccess.class);
    final BookingContext context = mock(BookingContext.class);
    final WaitlistService waitlist = mock(WaitlistService.class);
    final PackBalanceService packs = mock(PackBalanceService.class);
    final PlanService plans = mock(PlanService.class);
    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final BillingCensusAccess billing = mock(BillingCensusAccess.class);
    final PriceResolver prices = mock(PriceResolver.class);
    final DemoMembers members = new DemoMembers(census, context, mock(SeatHoldService.class), mock(BookingConfirmationService.class),
            mock(BookingCancellationService.class), waitlist, packs, plans, classes, billing, prices);
    static final DemoMembers.Candidate NURIA = new DemoMembers.Candidate("member-9", "account-9", "Núria", "dog-9", "level-ini");

    static ActivityMemberAccess.Member member(String id, String number, String name, List<ActivityMemberAccess.Dog> dogs) {
        return new ActivityMemberAccess.Member(id, "account-" + number, name, "Prova", number, "ACTIVE", true, null, Map.of(), dogs, List.of(), List.of(),
                List.of(id + "@example.test"));
    }
    void classStarts() {
        when(context.zone()).thenReturn(MADRID);
        when(classes.require("class-1")).thenReturn(new ClassSessionBookingAccess.Session("class-1", "ACTIVE", CLASS_DATE, "18:00", "19:00", STARTS,
                STARTS.plusSeconds(3600), "ring-1", List.of("level-ini"), List.of("instructor-1"), 5, 0, 0, false, null, 1L));
    }

    @Test void E11_T06_thePoolFollowsTheMemberNumberAsANumberNotAsText() {
        when(census.activeIds()).thenReturn(List.of("member-10", "member-9"));
        when(census.member("member-10")).thenReturn(member("member-10", "10", "Marc Prova", List.of(new ActivityMemberAccess.Dog("dog-10", "ACTIVE", "level-ini"))));
        when(census.member("member-9")).thenReturn(member("member-9", "9", "Núria Prova", List.of(new ActivityMemberAccess.Dog("dog-9", "ACTIVE", "level-ini"))));
        var pool = members.pool(Set.of());
        assertThat(pool).extracting(DemoMembers.Candidate::memberId).containsExactly("member-9", "member-10");
        assertThat(pool.getFirst()).isEqualTo(NURIA);
    }

    @Test void E11_T06_thePoolLeavesOutDogsThatAreNotActive() {
        when(census.activeIds()).thenReturn(List.of("member-9"));
        when(census.member("member-9")).thenReturn(member("member-9", "9", "Núria Prova", List.of(new ActivityMemberAccess.Dog("dog-9", "ACTIVE", "level-ini"),
                new ActivityMemberAccess.Dog("dog-8", "INACTIVE", "level-ini"), new ActivityMemberAccess.Dog("dog-7", "PENDING", "level-ini"))));
        assertThat(members.pool(Set.of())).extracting(DemoMembers.Candidate::dogId).containsExactly("dog-9");
    }

    @Test void E11_T06_aMemberWithoutAnActiveDogOfTheLevelFailsWithNotFound() {
        when(census.member("member-9")).thenReturn(member("member-9", "9", "Núria Prova", List.of(new ActivityMemberAccess.Dog("dog-9", "ACTIVE", "level-adv"))));
        assertThatThrownBy(() -> members.member("member-9", "level-ini"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(e.details()).containsEntry("demoMember", "member-9").containsEntry("levelId", "level-ini");
                });
    }

    @Test void E11_T06_aBookingOfAMemberWithoutAPackPlanNeverOpensAPack() {
        classStarts();
        when(packs.enabled()).thenReturn(true);
        when(packs.balance("member-9", "dog-9", CLASS_DATE)).thenReturn(Optional.empty()); // MONTHLY plan: no balance (R-08-17)
        when(plans.activePackId()).thenReturn(Optional.of("plan-pack"));
        members.preparePack("class-1", NURIA, false, AT);
        verify(packs, never()).open(any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(plans, billing);
    }

    @Test void E11_T06_aWithPackRowInAClubWithoutAnActivePackPlanFailsWithTheSeedMessage() {
        classStarts();
        when(packs.enabled()).thenReturn(true);
        when(packs.balance("member-9", "dog-9", CLASS_DATE)).thenReturn(Optional.empty());
        when(plans.activePackId()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> members.preparePack("class-1", NURIA, true, AT))
                .isInstanceOf(IllegalStateException.class).hasMessage("Demo bookings need a seeded pack plan");
        verifyNoInteractions(billing);
    }

    @Test void E11_T06_aPackMemberWithoutBalanceAndNoActivePackPlanFailsWithTheSeedMessage() {
        classStarts();
        when(packs.enabled()).thenReturn(true);
        // A member on a PACK plan that was deactivated since: no usable balance and no active PACK plan to open one from.
        when(packs.balance("member-9", "dog-9", CLASS_DATE)).thenReturn(Optional.of(new PackBalanceService.Balance(null, 0, 0, 0, null)));
        when(plans.activePackId()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> members.preparePack("class-1", NURIA, false, AT))
                .isInstanceOf(IllegalStateException.class).hasMessage("Demo bookings need a seeded pack plan");
        verify(packs, never()).open(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test void E11_T06_joinReturnsTheWaitingEntryCreatedAsTheMember() throws ReflectiveOperationException {
        // The seed actor's profile guard is static and shared by every test of the JVM: put back whatever was bound before.
        var guard = demoProfileGuard(); var previous = guard.get();
        try {
            new DemoSeedActor.ProfileGuard().setEnvironment(test());
            when(packs.enabled()).thenReturn(false);
            when(context.asOf(eq(AT), any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
            var entry = new WaitlistEntry("entry-1", "club-1", "class-1", "dog-9", "member-9", "account-9", AT, WaitlistState.ACTIVE, 1, null, null, null,
                    null, null, null, STARTS, "2026-10-11", 0L, AT, "account-9", AT, "account-9");
            when(waitlist.join(any(), eq("class-1"), eq("dog-9"))).thenReturn(entry);
            assertThat(members.join("class-1", NURIA, AT)).isSameAs(entry);
            verify(waitlist).join(any(), eq("class-1"), eq("dog-9"));
        } finally {
            guard.set(previous);
        }
    }

    static MockEnvironment test() { var environment = new MockEnvironment(); environment.setActiveProfiles("test"); return environment; }
    @SuppressWarnings("unchecked")
    static AtomicReference<Environment> demoProfileGuard() throws ReflectiveOperationException {
        var field = DemoSeedActor.class.getDeclaredField("ENVIRONMENT"); field.setAccessible(true);
        return (AtomicReference<Environment>) field.get(null);
    }
}
