package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.BookingCancelReason;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.BookingWeeks;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.MemberService;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.DemoSeedStep;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link DemoScenarioSeeder} (E5-T06 scenario, S08 half): seed 42 picks the same non-login
 * registrants for every class row, a row the pool cannot fill fails, a missing class fails with NOT_FOUND, and a
 * cancellation is followed by the R-08-11 offer of exactly the freed seats, only to ACTIVE waiting entries.
 */
class DemoScenarioSeederSurvivorsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final LocalDate WEEK_START = LocalDate.parse("2026-10-12");
    static final LocalDate TODAY = LocalDate.parse("2026-10-09");
    /** Monday 2026-10-12 and Tuesday 2026-10-13, 18:00 Madrid; both booking weeks open on Sunday 2026-10-11 20:00 Madrid (18:00Z). */
    static final Instant MONDAY_STARTS = Instant.parse("2026-10-12T16:00:00Z");
    static final Instant TUESDAY_STARTS = Instant.parse("2026-10-13T16:00:00Z");
    static final Instant OPENED = Instant.parse("2026-10-11T18:00:00Z");
    /** `cancelAt` Monday 10:00 Madrid, eight hours before the class. */
    static final Instant CANCEL_AT = Instant.parse("2026-10-12T08:00:00Z");

    final ClassSessionService sessions = mock(ClassSessionService.class);
    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final PlanningCatalogAccess catalogs = mock(PlanningCatalogAccess.class);
    final BookingContext context = mock(BookingContext.class);
    final DemoMembers members = mock(DemoMembers.class);
    final WaitlistService waitlist = mock(WaitlistService.class);
    final BookingRepository bookings = mock(BookingRepository.class);
    final WaitlistEntryRepository entries = mock(WaitlistEntryRepository.class);
    final DemoScenarioSeeder seeder = new DemoScenarioSeeder(sessions, classes, catalogs, context, members, waitlist, bookings, entries,
            mock(MemberService.class), new ObjectMapper().findAndRegisterModules());

    static DemoMembers.Candidate candidate(String n, String level) {
        return new DemoMembers.Candidate("member-" + n, "account-" + n, "Soci" + n, "dog-" + n, level);
    }
    static final DemoMembers.Candidate LIA = new DemoMembers.Candidate("member-lia", "account-lia", "Lia", "dog-nala", "level-a");

    static Map<String, Object> classRow(String day, int booked, int waiting) {
        var row = new LinkedHashMap<String, Object>();
        row.put("day", day); row.put("start", "18:00"); row.put("ring", "R1"); row.put("fill", false);
        row.put("booked", booked); row.put("waiting", waiting); row.put("riskExempt", false);
        return row;
    }
    static DemoSeedStep.Input input(Map<String, Object> scenario) {
        return new DemoSeedStep.Input(Map.of("scenario", scenario), 42, WEEK_START, "account-admin", Set.of("member-login"),
                List.of("member-login", "member-lia"), TODAY, false);
    }
    static ClassSessionBookingAccess.Session session(String id, LocalDate date, Instant startsAt, String level) {
        return new ClassSessionBookingAccess.Session(id, "ACTIVE", date, "18:00", "19:00", startsAt, startsAt.plusSeconds(3600), "ring-1",
                List.of(level), List.of("instructor-1"), 5, 0, 0, false, null, 1L);
    }
    static Booking booking(String id, BookingState state, Instant cancelledAt) { return booking(id, "dog-" + id, state, cancelledAt); }
    static Booking booking(String id, String dogId, BookingState state, Instant cancelledAt) {
        return new Booking(id, "club-1", "class-1", dogId, "member-" + id, state, BookingOrigin.APP, OPENED, new Booking.Actor("account-" + id, null, "Soci"),
                MONDAY_STARTS, MONDAY_STARTS.plusSeconds(3600), "2026-10-11", cancelledAt,
                cancelledAt == null ? null : new Booking.Canceller("account-" + id, ActorRole.MEMBER, "Soci", null),
                cancelledAt == null ? null : BookingCancelReason.MEMBER, null, cancelledAt == null ? null : Boolean.FALSE, cancelledAt == null ? null : 480,
                null, null, null, null, null, null, null, 1L, OPENED, "account-" + id, cancelledAt == null ? OPENED : cancelledAt, "account-" + id);
    }
    /** A NOTIFIED entry holds the seat an earlier cancellation released (offered ten minutes before `cancelAt`, still open). */
    static WaitlistEntry entry(WaitlistState state) {
        boolean notified = state == WaitlistState.NOTIFIED; var notifiedAt = CANCEL_AT.minusSeconds(600);
        return new WaitlistEntry("entry-1", "club-1", "class-1", "dog-w", "member-w", "account-w", OPENED, state, 1,
                notified ? notifiedAt : null, notified ? notifiedAt.plusSeconds(3600) : null, null, null, null, null, MONDAY_STARTS, "2026-10-11",
                notified ? 1L : 0L, OPENED, "account-w", notified ? notifiedAt : OPENED, "account-w");
    }

    @BeforeEach void setUp() {
        when(context.zone()).thenReturn(MADRID);
        when(context.weeks()).thenReturn(new BookingWeeks(new BookingWeeks.Opening(DayOfWeek.SUNDAY, LocalTime.of(20, 0)), MADRID));
        when(catalogs.ringIdsByShortName()).thenReturn(Map.of("R1", "ring-1"));
        when(catalogs.levelIdsByCode()).thenReturn(Map.of("INI", "level-a"));
        when(sessions.slot(WEEK_START, "18:00", "ring-1"))
                .thenReturn(Optional.of(new ClassSessionService.Slot("class-1", WEEK_START, "18:00", List.of("level-a"), 5, "ACTIVE", false)));
        when(sessions.slot(WEEK_START.plusDays(1), "18:00", "ring-1"))
                .thenReturn(Optional.of(new ClassSessionService.Slot("class-2", WEEK_START.plusDays(1), "18:00", List.of("level-b"), 5, "ACTIVE", false)));
        when(classes.require("class-1")).thenReturn(session("class-1", WEEK_START, MONDAY_STARTS, "level-a"));
        when(classes.require("class-2")).thenReturn(session("class-2", WEEK_START.plusDays(1), TUESDAY_STARTS, "level-b"));
        var pool = Stream.concat(Stream.of("a1", "a2", "a3", "a4", "a5", "a6").map(n -> candidate(n, "level-a")),
                Stream.of("b1", "b2", "b3", "b4", "b5", "b6").map(n -> candidate(n, "level-b"))).toList();
        when(members.pool(Set.of("member-login"))).thenReturn(pool);
        when(members.member("member-lia", "level-a")).thenReturn(LIA);
    }

    @Test void E11_T06_seed42ShufflesEachClassRowWithItsOwnIndex() {
        var counts = seeder.apply(input(Map.of("classes", List.of(classRow("MONDAY", 1, 0), classRow("TUESDAY", 1, 0)))));
        // Row 0: `new Random(42 * 37 + 0)` → a2 first (unshuffled: a1; `new Random(1)`: a5).
        verify(members).book("class-1", candidate("a2", "level-a"), OPENED.plusSeconds(1));
        // Row 1: `new Random(42 * 37 + 1)` → b1 first (`new Random(42 * 37 - 1)`: b2; `new Random(42 / 37 + 1)`: b6).
        verify(members).book("class-2", candidate("b1", "level-b"), OPENED.plusSeconds(2));
        verify(members, times(2)).book(any(), any(), any());
        assertThat(counts).containsEntry("scenarioClassBookings", 2);
    }

    @Test void E11_T06_aClassRowThePoolCannotFillForBookedPlusWaitingFailsWithNotFound() {
        when(members.pool(Set.of("member-login"))).thenReturn(List.of(candidate("a1", "level-a")));
        assertThatThrownBy(() -> seeder.apply(input(Map.of("classes", List.of(classRow("MONDAY", 1, 1))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND); assertThat(e.details()).containsKey("scenarioClass");
                });
        verify(members, never()).book(any(), any(), any());
    }

    @Test void E11_T06_aClassRowWhoseClassDoesNotExistFailsWithNotFound() {
        when(sessions.slot(WEEK_START, "18:00", "ring-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> seeder.apply(input(Map.of("classes", List.of(classRow("MONDAY", 1, 0))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND); assertThat(e.details()).containsEntry("slot", "MONDAY 18:00");
                });
    }

    /** One login member books Monday's class at the opening and cancels it at 10:00; by default the full class (5/5) keeps four live bookings. */
    Map<String, Object> cancellation() {
        when(context.enabled(Module.WAITLIST)).thenReturn(true);
        when(context.integer("waitlist.notifyThresholdMinutes")).thenReturn(30);
        when(context.asOf(any(), any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
        when(members.book("class-1", LIA, OPENED.plusSeconds(1))).thenReturn(booking("lia", "dog-nala", BookingState.ACTIVE, null));
        when(members.cancel("lia", LIA, CANCEL_AT)).thenReturn(booking("lia", "dog-nala", BookingState.CANCELLED, CANCEL_AT));
        when(bookings.forClass("class-1", BookingRepository.LIVE)).thenReturn(List.of(booking("b1", BookingState.ACTIVE, null),
                booking("b2", BookingState.ACTIVE, null), booking("b3", BookingState.ACTIVE, null), booking("b4", BookingState.ACTIVE, null)));
        var item = new LinkedHashMap<String, Object>();
        item.put("member", 1); item.put("dog", "INI"); item.put("day", "MONDAY"); item.put("start", "18:00"); item.put("ring", "R1");
        item.put("cancelAt", Map.of("week", 0, "day", "MONDAY", "time", "10:00"));
        return Map.of("classBookings", List.of(item));
    }

    @Test void E11_T06_aCancellationOffersExactlyTheFreedSeatAndCountsTheOffersMade() {
        var scenario = cancellation();
        when(entries.live("class-1")).thenReturn(List.of(entry(WaitlistState.ACTIVE)));
        when(waitlist.offerSeats("class-1", 1)).thenReturn(1);
        var counts = seeder.apply(input(scenario));
        verify(waitlist).offerSeats("class-1", 1);
        verify(waitlist, times(1)).offerSeats(anyString(), anyInt());
        assertThat(counts).containsEntry("scenarioCancellations", 1).containsEntry("scenarioOffers", 1);
    }

    @Test void E11_T06_aCancellationWithOnlyAnOpenOfferMakesNoNewOffer() {
        var scenario = cancellation();
        // An earlier cancellation released a seat that the only live entry now holds (NOTIFIED, FIFO): after Lia's
        // cancellation three bookings stay live, two seats are free, and nobody ACTIVE is waiting.
        when(bookings.forClass("class-1", BookingRepository.LIVE)).thenReturn(List.of(booking("b1", BookingState.ACTIVE, null),
                booking("b2", BookingState.ACTIVE, null), booking("b3", BookingState.ACTIVE, null)));
        when(entries.live("class-1")).thenReturn(List.of(entry(WaitlistState.NOTIFIED)));
        var counts = seeder.apply(input(scenario));
        verify(waitlist, never()).offerSeats(anyString(), anyInt());
        assertThat(counts).containsEntry("scenarioCancellations", 1).containsEntry("scenarioOffers", 0);
    }
}
