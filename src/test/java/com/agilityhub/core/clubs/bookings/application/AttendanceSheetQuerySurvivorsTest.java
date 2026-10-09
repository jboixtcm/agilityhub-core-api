package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.DogFollowupPort;
import com.agilityhub.core.clubs.bookings.domain.AttendanceWindow;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.WaitlistMode;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.AttendanceCensusAccess;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.Module;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link AttendanceSheetQuery} (S10 R-10-02, R-10-06, R-10-16; T-10-08, T-10-10, T-10-33).
 * The R-10-06 cases use instants already in the past, so a mutant of the `while` at line 115 fails by assertion instead
 * of looping (with a first candidate still ahead, the negated condition never becomes false). A stored `noShowNotice`
 * always has `queuedAt`: the only writer of the subdocument is the claim, which sets `queuedAt` and `eventId` together
 * (`AttendanceRepository#claimForNoShowNotice`); before it the whole subdocument is null.
 */
class AttendanceSheetQuerySurvivorsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    /** Monday 2026-10-05 10:00 Madrid (CEST, +02:00). */
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final LocalDate CLASS_DATE = LocalDate.parse("2026-10-01");
    /** The class of the sheet: today 11:00-12:00 Madrid, not started yet (the sheet opens at the start of the class day). */
    static final LocalDate SHEET_DATE = LocalDate.parse("2026-10-05");
    static final Instant STARTS_AT = Instant.parse("2026-10-05T09:00:00Z");

    final BookingContext context = mock(BookingContext.class);
    final AttendanceStatusCalculator calculator = mock(AttendanceStatusCalculator.class);
    final InstructorScheduleAccess schedule = mock(InstructorScheduleAccess.class);
    final BookingRepository bookings = mock(BookingRepository.class);
    final AttendanceRepository attendances = mock(AttendanceRepository.class);
    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final AttendanceCensusAccess census = mock(AttendanceCensusAccess.class);
    final PlanningCatalogAccess catalogs = mock(PlanningCatalogAccess.class);
    final DogFollowupPort followup = mock(DogFollowupPort.class);
    final AttendanceSheetQuery query = new AttendanceSheetQuery(context, calculator, schedule, bookings, attendances, waitlist, census, catalogs, followup);
    final AttendanceCaller caller = new AttendanceCaller("account-neus", "Neus", false, "instructor-neus");

    // --- R-10-06 noShowNotice (lines 112 and 115) --------------------------------------------------------------------------

    @Test void T_10_08_aNoticeNotQueuedYetGoesToTheFirstRunStillAheadOfNow() {
        // An ADMIN marked the NO_SHOW at 09:30 on the 5th (R-10-03 override), after that day's 09:00 run: nothing claimed it
        // yet. The 09:00 runs of 02, 03, 04 and 05 October (07:00Z) are already past at NOW: the next one is the 6th.
        var out = AttendanceSheetQuery.noShowNotice(CLASS_DATE, null, "09:00", MADRID, NOW);

        assertThat(out.get("scheduledFor")).isEqualTo(Instant.parse("2026-10-06T07:00:00Z"));
        assertThat(out.get("queuedAt")).isNull();
        assertThat(out.get("sentAt")).isNull();
    }

    @Test void T_10_08_aQueuedNoticeIsScheduledForTheRunOfTheDayItWasQueued() {
        // The 09:00 run of the 2nd claimed the mark (NoShowNoticeClaims: queuedAt = the run's now) and S11 delivered it.
        var queued = Instant.parse("2026-10-02T07:00:03Z");
        var sent = Instant.parse("2026-10-02T07:00:41Z");

        var out = AttendanceSheetQuery.noShowNotice(CLASS_DATE, new Attendance.NoShowNotice(queued, "event-1", sent), "09:00", MADRID, NOW);

        assertThat(out.get("scheduledFor")).isEqualTo(Instant.parse("2026-10-02T07:00:00Z"));
        assertThat(out.get("queuedAt")).isEqualTo(queued);
        assertThat(out.get("sentAt")).isEqualTo(sent);
    }

    // --- build: instructorName (line 59) and the waiting list's handler (line 128) ------------------------------------------

    @Test void T_10_10_theSheetShowsTheInstructorNamesAndNullWithoutInstructors() {
        stubSheet(false);

        // No bookings yet: the class's counters are 0 and the R-10-02 rows are empty.
        assertThat(classSession(query.build(cls(List.of("instructor-marc", "instructor-neus"), "Marc, Neus", 6, 0, 0), caller)))
                .containsEntry("instructorName", "Marc, Neus");
        // A class without instructors: `InstructorScheduleAccess.views` joins no names, so `instructorNames` is "".
        assertThat(classSession(query.build(cls(List.of(), "", 6, 0, 0), caller))).containsEntry("instructorName", null);
    }

    @Test void T_10_33_theWaitlistEntriesCarryTheHandlerOfTheirDog() {
        stubSheet(true);
        // A full class (1/1, the one live booking below) with one dog waiting.
        when(bookings.forClass("class-1")).thenReturn(List.of(booking()));
        when(census.pairs(List.of("dog-1"))).thenReturn(Map.of("dog-1", new AttendanceCensusAccess.Pair("dog-1", "Duna", null, null, "F", null, null,
                "ACTIVE", null, null, "member-1", "Joan", "Joan Serra", null)));
        when(context.waitlistMode()).thenReturn(WaitlistMode.ALL_AT_ONCE);
        when(waitlist.live("class-1")).thenReturn(List.of(entry()));
        var pair = new AttendanceCensusAccess.Pair("dog-2", "Kira", "Pau Vidal", null, "F", null, null, "ACTIVE", null, null, "member-2", "Eva",
                "Eva Puig", null);
        when(census.pairs(List.of("dog-2"))).thenReturn(Map.of("dog-2", pair));

        var out = query.build(cls(List.of("instructor-marc"), "Marc", 1, 1, 1), caller);

        @SuppressWarnings("unchecked") var section = (Map<String, Object>) out.get("waitlist");
        @SuppressWarnings("unchecked") var entries = (List<Map<String, Object>>) section.get("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst()).containsEntry("entryId", "entry-1").containsEntry("dogName", "Kira").containsEntry("memberFirstName", "Eva")
                .containsEntry("handlerName", "Pau Vidal");
    }

    private void stubSheet(boolean waitlistOn) {
        when(context.now()).thenReturn(NOW);
        when(context.zone()).thenReturn(MADRID);
        when(context.enabled(Module.WAITLIST)).thenReturn(waitlistOn);
        var config = mock(ClubConfig.class);
        when(config.get("messaging.noShowNoticeTime", String.class)).thenReturn("09:00");
        when(context.config()).thenReturn(config);
        // AttendanceStatusCalculator#window: AttendanceWindow.of(date, zone, attendance.editDays = 1, the catalog default).
        when(calculator.window(SHEET_DATE)).thenReturn(AttendanceWindow.of(SHEET_DATE, MADRID, 1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> classSession(Map<String, Object> sheet) { return (Map<String, Object>) sheet.get("classSession"); }

    private static InstructorScheduleAccess.ClassView cls(List<String> instructorIds, String instructorNames, int capacity, int booked, int waiting) {
        return new InstructorScheduleAccess.ClassView("class-1", "ACTIVE", SHEET_DATE, "11:00", "12:00", STARTS_AT, STARTS_AT.plusSeconds(3600), null,
                instructorIds, instructorNames, "Agility · Iniciació", List.of(), capacity, booked, waiting, InstructorScheduleAccess.Summary.EMPTY);
    }

    private static Booking booking() {
        var booked = NOW.minusSeconds(86_400);
        return new Booking("booking-1", "club-a", "class-1", "dog-1", "member-1", BookingState.ACTIVE, BookingOrigin.APP, booked,
                new Booking.Actor("account-joan", null, "Joan"), STARTS_AT, STARTS_AT.plusSeconds(3600), "2026-10-04",
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null,
                0L, booked, "account-joan", booked, "account-joan");
    }

    private static WaitlistEntry entry() {
        return new WaitlistEntry("entry-1", "club-a", "class-1", "dog-2", "member-2", "account-eva", NOW.minusSeconds(7200), WaitlistState.ACTIVE, 1,
                null, null, null, null, null, null, STARTS_AT, "2026-10-04", 0L, NOW.minusSeconds(7200), "account-eva",
                NOW.minusSeconds(7200), "account-eva");
    }
}
