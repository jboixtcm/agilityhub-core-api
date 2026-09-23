package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.ActivityMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionService;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** E4-T05 unit rules of the demo bookings adapter and its seed step (the seeded happy path is covered by DemoPlanningSeedIT). */
class DemoBookingsTest {
    static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");
    static final LocalDate MONDAY = LocalDate.parse("2026-09-07");
    DemoClassBookingRepository repository = mock(DemoClassBookingRepository.class);
    ClassSessionService sessions = mock(ClassSessionService.class);
    DemoClassBookings adapter = new DemoClassBookings(repository, sessions, Clock.fixed(NOW, ZoneOffset.UTC));
    TenantContext.Scope tenant;

    @BeforeEach void open() { tenant = TenantContext.open("club-fixture"); when(repository.insert(any())).thenAnswer(i -> i.getArgument(0)); }
    @AfterEach void close() { tenant.close(); }

    static DemoClassBooking booking(String id, String dog, DemoClassBooking.State state, Integer position) {
        return new DemoClassBooking(id, "club-fixture", "class-1", "member-" + id, dog, state, false, position, null, null, 0L, NOW, NOW);
    }

    @Test void T_06_28_demoAdapterKeepsFifoWaitlistAndRejectsTheSameDogTwice() {
        when(repository.forClass("class-1", DemoClassBooking.State.ACTIVE)).thenReturn(List.of(booking("a", "dog-a", DemoClassBooking.State.ACTIVE, null)));
        when(repository.forClass("class-1", DemoClassBooking.State.WAITLISTED)).thenReturn(List.of(booking("w", "dog-w", DemoClassBooking.State.WAITLISTED, 1)));
        for (String dog : List.of("dog-a", "dog-w")) {
            assertThatThrownBy(() -> adapter.book("class-1", "member-x", dog, false, false))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ALREADY_REGISTERED));
        }
        adapter.book("class-1", "member-x", "dog-x", true, true);
        verify(repository).insert(argThat(b -> b.state() == DemoClassBooking.State.WAITLISTED && b.position() == 2 && !b.paidWithPack()));
        verify(sessions).bookingCounters("class-1", 1, 2);
        adapter.book("class-1", "member-y", "dog-y", true, false);
        verify(repository).insert(argThat(b -> b.state() == DemoClassBooking.State.ACTIVE && b.position() == null && b.paidWithPack()));
        verify(sessions).bookingCounters("class-1", 2, 1);
        verify(sessions, times(2)).bookingCounters(anyString(), anyInt(), anyInt());
    }

    @Test void T_06_28_demoAdapterResolvesWaitlistEntriesInRequestOrderAndCancelsEveryRegistrant() {
        assertThat(adapter.waitlistEntries(List.of())).isEmpty();
        when(repository.byIds(List.of("w2", "w1", "w2", "a"))).thenReturn(List.of(booking("w1", "dog-1", DemoClassBooking.State.CANCELLED, 1),
                booking("a", "dog-a", DemoClassBooking.State.CANCELLED_BY_CLUB, null), booking("w2", "dog-2", DemoClassBooking.State.CANCELLED, 2)));
        assertThat(adapter.waitlistEntries(List.of("w2", "w1", "w2", "a"))).extracting(e -> e.dogId()).containsExactly("dog-2", "dog-1");

        var active = booking("a", "dog-a", DemoClassBooking.State.ACTIVE, null); var waiting = booking("w", "dog-w", DemoClassBooking.State.WAITLISTED, 1);
        when(repository.forClass("class-1", DemoClassBooking.State.ACTIVE)).thenReturn(List.of(active));
        when(repository.forClass("class-1", DemoClassBooking.State.WAITLISTED)).thenReturn(List.of(waiting));
        assertThat(adapter.activeBookings("class-1")).extracting(b -> b.dogId()).containsExactly("dog-a");
        assertThat(adapter.liveWaitlist("class-1")).extracting(w -> w.dogId()).containsExactly("dog-w");
        var effects = adapter.cancelAllByClub("class-1", "CLUB_MANUAL", "admin");
        assertThat(effects.bookings()).hasSize(1); assertThat(effects.waitlist()).hasSize(1);
        verify(repository).update(argThat(b -> b.id().equals("a") && b.state() == DemoClassBooking.State.CANCELLED_BY_CLUB && NOW.equals(b.cancelledAt())), eq(0L));
        verify(repository).update(argThat(b -> b.id().equals("w") && b.state() == DemoClassBooking.State.CANCELLED && "CLUB_MANUAL".equals(b.cancelReason())), eq(0L));
    }

    @Test void T_06_28_demoBookingSeedGuardsRowsSlotsCandidatesAndAdapter() {
        @SuppressWarnings("unchecked") ObjectProvider<DemoClassBookings> provider = mock(ObjectProvider.class);
        var catalogs = mock(PlanningCatalogAccess.class); var members = mock(ActivityMemberAccess.class);
        var seeder = new DemoBookingSeeder(provider, sessions, catalogs, members, new ObjectMapper());
        assertThat(seeder.order()).isEqualTo(20);
        assertThat(seeder.apply(input(List.of()))).containsEntry("classBookings", 0).containsEntry("classWaitlist", 0);
        verifyNoInteractions(provider);

        var row = Map.<String, Object>of("week", 1, "day", "WEDNESDAY", "start", "18:50", "ring", "CEN", "booked", 1, "withPack", 1, "waiting", 1);
        assertThatThrownBy(() -> seeder.apply(input(List.of(row)))).isInstanceOf(IllegalStateException.class);

        when(provider.getIfAvailable()).thenReturn(adapter);
        when(catalogs.ringIdsByShortName()).thenReturn(Map.of("CEN", "ring-cen"));
        var wednesday = MONDAY.plusWeeks(1).plusDays(2);
        when(sessions.slot(wednesday, "18:50", "ring-cen")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> seeder.apply(input(List.of(row)))).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));

        when(sessions.slot(wednesday, "18:50", "ring-cen")).thenReturn(Optional.of(new ClassSessionService.Slot("class-1", wednesday, "18:50", List.of("level-b"), 5, "ACTIVE")));
        when(members.activeIds()).thenReturn(List.of("m-login", "m-10", "m-2", "m-noaccount", "m-inactive"));
        when(members.member("m-10")).thenReturn(member("m-10", "10", "acc-10", true, new ActivityMemberAccess.Dog("d-10", "ACTIVE", "level-b")));
        when(members.member("m-2")).thenReturn(member("m-2", "2", "acc-2", true,
                new ActivityMemberAccess.Dog("d-2b", "INACTIVE", "level-b"), new ActivityMemberAccess.Dog("d-2a", "ACTIVE", "level-b")));
        when(members.member("m-noaccount")).thenReturn(member("m-noaccount", "3", null, true, new ActivityMemberAccess.Dog("d-3", "ACTIVE", "level-b")));
        when(members.member("m-inactive")).thenReturn(member("m-inactive", "4", "acc-4", false, new ActivityMemberAccess.Dog("d-4", "ACTIVE", "level-b")));
        when(repository.forClass(anyString(), any())).thenReturn(List.of());
        var counts = seeder.apply(input(List.of(row)));
        assertThat(counts).containsEntry("classBookings", 1).containsEntry("classWaitlist", 1);
        verify(repository).insert(argThat(b -> b.dogId().equals("d-2a")));
        verify(repository).insert(argThat(b -> b.dogId().equals("d-10")));
        verify(repository, times(2)).insert(any());
        verify(members, never()).member("m-login");

        var tooMany = new HashMap<>(row); tooMany.put("booked", 3);
        assertThatThrownBy(() -> seeder.apply(input(List.of(tooMany)))).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        for (var invalid : List.of(Map.of("booked", -1), Map.of("waiting", -1), Map.of("withPack", -1), Map.of("withPack", 2))) {
            var bad = new HashMap<>(row); bad.putAll(invalid);
            assertThatThrownBy(() -> seeder.apply(input(List.of(bad)))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    static DemoSeedStep.Input input(List<Map<String, Object>> rows) {
        return new DemoSeedStep.Input(Map.of("bookings", rows), 42, MONDAY, "admin", Set.of("m-login"));
    }
    static ActivityMemberAccess.Member member(String id, String number, String account, boolean active, ActivityMemberAccess.Dog... dogs) {
        return new ActivityMemberAccess.Member(id, account, "Fictional", "Member", number, "ACTIVE", active, null, null, List.of(dogs), List.of(), List.of(), List.of());
    }
}
