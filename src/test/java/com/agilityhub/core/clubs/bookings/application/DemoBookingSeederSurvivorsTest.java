package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingWeeks;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionService;
import com.agilityhub.core.shared.application.DemoSeedStep;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link DemoBookingSeeder} (E4-T05 registrants of the seeded classes, E5-T06 waiting rows,
 * E5-T09/E5-T14 re-anchor): what the step writes through {@link DemoMembers} and the S06 class edit for realistic rows.
 */
class DemoBookingSeederSurvivorsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final LocalDate MONDAY = LocalDate.parse("2026-10-12");
    static final LocalDate TODAY = LocalDate.parse("2026-10-09");
    /** Friday 2026-10-09 12:00 Madrid: the week of Monday 12 opened on Sunday 2026-10-04 20:00, so its classes are bookable now. */
    static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    /** Monday 2026-10-12 18:00 Madrid. */
    static final Instant STARTS = Instant.parse("2026-10-12T16:00:00Z");

    final ClassSessionService sessions = mock(ClassSessionService.class);
    final PlanningCatalogAccess catalogs = mock(PlanningCatalogAccess.class);
    final BookingContext context = mock(BookingContext.class);
    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final DemoMembers members = mock(DemoMembers.class);
    final DemoBookingSeeder seeder = new DemoBookingSeeder(sessions, catalogs, new ObjectMapper().findAndRegisterModules(), context, classes, members);

    static DemoMembers.Candidate candidate(String n) {
        return new DemoMembers.Candidate("member-" + n, "account-" + n, "Soci" + n, "dog-" + n, "level-ini");
    }
    static final List<DemoMembers.Candidate> POOL = List.of(candidate("a1"), candidate("a2"), candidate("a3"), candidate("a4"), candidate("a5"), candidate("a6"));

    static Map<String, Object> row(int booked, int withPack, int waiting) {
        var row = new LinkedHashMap<String, Object>();
        row.put("week", 0); row.put("day", "MONDAY"); row.put("start", "18:00"); row.put("ring", "R1");
        row.put("booked", booked); row.put("withPack", withPack); row.put("waiting", waiting);
        return row;
    }
    static DemoSeedStep.Input input(List<Map<String, Object>> rows) {
        return new DemoSeedStep.Input(Map.of("bookings", rows), 42, MONDAY, "account-admin", Set.of(), List.of(), TODAY, false);
    }
    static ClassSessionService.Slot slot(String state, int capacity, boolean manual) {
        return new ClassSessionService.Slot("class-1", MONDAY, "18:00", List.of("level-ini"), capacity, state, manual);
    }
    static ClassSessionBookingAccess.Session session(String state, int capacity, int booked) {
        return new ClassSessionBookingAccess.Session("class-1", state, MONDAY, "18:00", "19:00", STARTS, STARTS.plusSeconds(3600), "ring-1",
                List.of("level-ini"), List.of("instructor-1"), capacity, booked, 0, false, null, 3L);
    }

    @BeforeEach void setUp() {
        when(catalogs.ringIdsByShortName()).thenReturn(Map.of("R1", "ring-1"));
        when(members.pool(Set.of())).thenReturn(POOL);
        when(context.now()).thenReturn(NOW);
        when(context.weeks()).thenReturn(new BookingWeeks(new BookingWeeks.Opening(DayOfWeek.SUNDAY, LocalTime.of(20, 0)), MADRID));
        when(sessions.slot(MONDAY, "18:00", "ring-1")).thenReturn(Optional.of(slot("ACTIVE", 5, false)));
        when(classes.require("class-1")).thenReturn(session("ACTIVE", 5, 0));
    }

    @Test void E11_T06_aSeedWithoutBookingRowsStillAnswersBothCounters() {
        var counts = seeder.apply(new DemoSeedStep.Input(Map.of("planning", Map.of()), 42, MONDAY, "account-admin", Set.of(), List.of(), TODAY, false));
        assertThat(counts).containsExactly(entry("classBookings", 0), entry("classWaitlist", 0));
        verifyNoInteractions(catalogs, members, sessions);
    }

    @Test void E11_T06_aReanchoredRunKeepsAGeneratedDraftClassWithoutBookingIt() {
        // A generated week the planning step left unvalidated (`validate: false`): its classes are DRAFT.
        when(sessions.slot(MONDAY, "18:00", "ring-1")).thenReturn(Optional.of(slot("DRAFT", 5, false)));
        when(classes.require("class-1")).thenReturn(session("DRAFT", 5, 0));
        var reanchor = new DemoSeedStep.Input(Map.of("bookings", List.of(row(1, 0, 0))), 42, MONDAY, "account-admin", Set.of(), List.of(), TODAY, true,
                new TreeSet<>(Set.of(0)));
        var counts = seeder.apply(reanchor);
        assertThat(counts).containsEntry("keptClasses", 1).containsEntry("classBookings", 0);
        verify(members, never()).book(any(), any(), any());
    }

    @Test void E11_T06_aRowWhoseClassDoesNotExistFailsWithNotFound() {
        when(sessions.slot(MONDAY, "18:00", "ring-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> seeder.apply(input(List.of(row(1, 0, 0)))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND); assertThat(e.details()).containsKey("slot");
                });
    }

    @Test void E11_T06_seed42PicksTheSameRegistrantAndLeavesTheCapacityAloneWithoutWaitingRows() {
        var counts = seeder.apply(input(List.of(row(1, 0, 0))));
        // `new Random(42 * 31 + 0)` shuffles the six eligible members to a4, a3, a5, a6, a2, a1 (`new Random(1)` would give a5 first).
        verify(members).book("class-1", candidate("a4"), NOW);
        verify(members, times(1)).book(any(), any(), any());
        verify(sessions, never()).patch(any(), anyLong(), any(), anyBoolean());
        verify(members, never()).join(any(), any(), any());
        assertThat(counts).containsEntry("classBookings", 1).containsEntry("classWaitlist", 0);
    }

    @Test void E11_T06_theClassIsBookedAtTheRunInstantOnceItsBookingWeekIsOpen() {
        seeder.apply(input(List.of(row(1, 0, 0))));
        var at = ArgumentCaptor.forClass(Instant.class);
        verify(members).book(eq("class-1"), any(), at.capture());
        assertThat(at.getValue()).isEqualTo(NOW).isNotEqualTo(Instant.parse("2026-10-04T18:00:00Z"));
    }

    @Test void E11_T06_aTooSmallPoolForBookedPlusWaitingFailsWithNotFound() {
        when(members.pool(Set.of())).thenReturn(List.of(candidate("a1")));
        assertThatThrownBy(() -> seeder.apply(input(List.of(row(1, 0, 1)))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(members, never()).book(any(), any(), any());
    }

    @Test void E11_T06_aWithPackRegistrantPreparesAPackBeforeBooking() {
        seeder.apply(input(List.of(row(1, 1, 0))));
        verify(members).preparePack("class-1", candidate("a4"), true, NOW);
        verify(members, never()).preparePack(any(), any(), eq(false), any());
    }

    @Test void E11_T06_aClassAlreadyFullByItsBookingsTakesItsWaitingEntriesWithoutACapacityEdit() {
        when(sessions.slot(MONDAY, "18:00", "ring-1")).thenReturn(Optional.of(slot("ACTIVE", 2, false)));
        when(classes.require("class-1")).thenReturn(session("ACTIVE", 2, 0));
        var counts = seeder.apply(input(List.of(row(2, 0, 1))));
        verify(sessions, never()).patch(any(), anyLong(), any(), anyBoolean());
        verify(members, times(2)).book(eq("class-1"), any(), eq(NOW));
        verify(members, times(1)).join(eq("class-1"), any(), eq(NOW));
        assertThat(counts).containsEntry("classBookings", 2).containsEntry("classWaitlist", 1);
    }

    @Test @SuppressWarnings("unchecked")
    void E11_T06_aRowWithFreeSeatsIsHeldAtItsBookedCountForTheWaitingEntriesAndGetsItsAutoCapacityBack() {
        seeder.apply(input(List.of(row(1, 0, 1))));
        ArgumentCaptor<Map<String, Object>> patches = ArgumentCaptor.forClass(Map.class);
        verify(sessions, times(2)).patch(eq("class-1"), eq(3L), patches.capture(), eq(false));
        var values = new ArrayList<>(patches.getAllValues());
        assertThat(values.get(0)).containsOnly(entry("capacity", (Object) 1));
        assertThat(values.get(1)).containsOnlyKeys("capacity");
        assertThat(values.get(1).get("capacity")).as("AUTO capacity: back to the levels").isNull();
        var order = inOrder(sessions, members);
        order.verify(sessions).patch(eq("class-1"), eq(3L), any(), eq(false));
        order.verify(members).join(eq("class-1"), any(), eq(NOW));
        order.verify(sessions).patch(eq("class-1"), eq(3L), any(), eq(false));
    }
}
