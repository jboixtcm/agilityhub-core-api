package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.InactivityPort;
import com.agilityhub.core.clubs.bookings.application.ports.MemberActivityRowsPort;
import com.agilityhub.core.clubs.bookings.application.ports.PackBalancePort;
import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.shared.application.CacheLoads;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.MockClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BookableClassesQuery} (S08 screen 04, R-08-23, T-08-13; S15 R-15-11 30 s cache): the
 * class labels expire on the injected clock, a member token without a census member is 404, and without `dogId` nor
 * `lastDogForClass` a member with only group dogs gets the first chip.
 */
class BookableClassesQuerySurvivorsTest {
    final MockClock clock = new MockClock(Instant.parse("2026-10-05T08:00:00Z"));
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final MemberDogs dogs = mock(MemberDogs.class);
    final BookableClassesQuery query = new BookableClassesQuery(mock(BookingContext.class), census, dogs, mock(BookableClassesCache.class),
            mock(BookingChecks.class), mock(BookingRepository.class), mock(WaitlistEntryRepository.class), mock(BookingViews.class),
            mock(PackBalancePort.class), mock(InactivityPort.class), mock(SingleClassChargePort.class), mock(MemberActivityRowsPort.class), clock);

    @Test void T_08_13_theClassLabelsExpireAfterTheirTtlOnTheInjectedClock() {
        @SuppressWarnings("unchecked")
        var labels = (CacheLoads<String, ClassSessionBookingAccess.Labels>) ReflectionTestUtils.getField(query, "labels");
        var loads = new AtomicInteger();
        Function<String, ClassSessionBookingAccess.Labels> loader = key -> {
            loads.incrementAndGet();
            return new ClassSessionBookingAccess.Labels("Agility · Iniciació", "Pista 1", "#2E7D32", List.of(), "Marc");
        };

        labels.get("club-a:class-1:3:ca", loader);
        clock.advance(Duration.ofSeconds(10));
        labels.get("club-a:class-1:3:ca", loader);
        assertThat(loads).hasValue(1);

        clock.advance(BookableClassesCache.TTL);
        labels.get("club-a:class-1:3:ca", loader);
        assertThat(loads).hasValue(2);
    }

    @Test void T_08_13_aMemberTokenWithoutACensusMemberIsNotFound() {
        // A MEMBER-role account provisioned without a census member (club-as-code seed account, MembershipService#setRoles
        // keeps memberId null): the token has no `memberId` claim and BookingMemberAccess#member(null) is empty.
        when(census.member(null)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> query.bookable(null, null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test void T_08_13_withoutARequestedOrLastDogAMemberWithOnlyGroupDogsGetsTheFirstChip() {
        // FAMILY_GROUP: a member without own dogs books for the group's ACTIVE dogs (MemberDogs#chips: own first, then by name).
        var chips = List.of(chip("dog-kira", "member-pere", "Pere"), chip("dog-toby", "member-pere", "Pere"));

        MemberDogs.Chip proposed = ReflectionTestUtils.invokeMethod(BookableClassesQuery.class, "select", chips, null, null);

        assertThat(proposed.id()).isEqualTo("dog-kira");
    }

    /** A group dog's chip: `own` false and the owner's first name (MemberDogs#chips). */
    private static MemberDogs.Chip chip(String dogId, String ownerId, String ownerFirstName) {
        return new MemberDogs.Chip(new BookingMemberAccess.Dog(dogId, Character.toUpperCase(dogId.charAt(4)) + dogId.substring(5), "F", ownerId, null,
                "ACTIVE"), null, false, ownerFirstName);
    }
}
