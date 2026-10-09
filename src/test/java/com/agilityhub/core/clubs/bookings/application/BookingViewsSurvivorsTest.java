package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.AttendanceStatePort;
import com.agilityhub.core.clubs.bookings.application.ports.PackBalancePort;
import com.agilityhub.core.clubs.bookings.domain.WaitlistMode;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.census.application.AttendanceCensusAccess;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.CensusClubSettings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BookingViews} (S08 §6, R-08-20, T-08-09; `GET /bookings/filter-values`, T-08-47): the class
 * card names the instructor only when the class has one, and a class filter value is labelled with its club-local start and
 * description (its id once the class is gone).
 */
class BookingViewsSurvivorsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");

    final BookingContext context = mock(BookingContext.class);
    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final AttendanceCensusAccess guides = mock(AttendanceCensusAccess.class);
    final BookingViews views = new BookingViews(context, classes, census, mock(PackBalancePort.class), mock(AttendanceStatePort.class),
            mock(BookingCalendarTokens.class), mock(CensusClubSettings.class), guides);

    @Test void T_08_09_staffSeeTheInstructorOnlyOfAClassThatHasOne() {
        when(context.now()).thenReturn(NOW);
        when(context.zone()).thenReturn(MADRID);
        when(context.waitlistMode()).thenReturn(WaitlistMode.FIFO);
        when(context.integer("bookings.showInstructorHoursBefore")).thenReturn(24);
        when(census.dog("dog-kira")).thenReturn(Optional.of(new BookingMemberAccess.Dog("dog-kira", "Kira", "FEMALE", "member-eva", "level-1", "ACTIVE")));
        stubClass("class-a", "Neus Prat");
        // ClassSessionBookingAccess#labels joins no names for a class without instructors.
        stubClass("class-b", "");

        var withInstructor = views.waitlistEntry(entry("class-a"), true);
        var withoutInstructor = views.waitlistEntry(entry("class-b"), true);

        assertThat(card(withInstructor)).containsEntry("instructorName", "Neus Prat");
        assertThat(card(withoutInstructor)).containsEntry("instructorName", null);
    }

    @Test void T_08_47_aClassFilterValueIsLabelledWithItsLocalStartAndDescription() {
        when(context.zone()).thenReturn(MADRID);
        stubClass("class-a", "Neus Prat");

        assertThat(views.classLabel("class-a")).isEqualTo("2026-10-07T18:00 · Classe B+C");
        assertThat(views.classLabel("class-gone")).isEqualTo("class-gone");
    }

    private void stubClass(String id, String instructorNames) {
        var session = new ClassSessionBookingAccess.Session(id, "ACTIVE", LocalDate.parse("2026-10-07"), "18:00", "19:00", STARTS, STARTS.plusSeconds(3600),
                "ring-1", List.of("level-1"), instructorNames.isEmpty() ? List.of() : List.of("instructor-neus"), 3, 3, 1, false, null, 3L);
        when(classes.find(id)).thenReturn(Optional.of(session));
        when(classes.labels(eq(session), any(Locale.class)))
                .thenReturn(new ClassSessionBookingAccess.Labels("Classe B+C", "Central", "#8FCE8F", List.of("C"), instructorNames, "ring-1"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> card(Map<String, Object> entry) { return (Map<String, Object>) entry.get("classSession"); }

    /** Eva's FIFO entry for Kira, still queued. */
    private static WaitlistEntry entry(String classId) {
        var joined = NOW.minusSeconds(7200);
        return new WaitlistEntry("entry-" + classId, "club-a", classId, "dog-kira", "member-eva", "account-eva", joined, WaitlistState.ACTIVE, 1,
                null, null, null, null, null, null, STARTS, "2026-10-04", 0L, joined, "account-eva", joined, "account-eva");
    }
}
