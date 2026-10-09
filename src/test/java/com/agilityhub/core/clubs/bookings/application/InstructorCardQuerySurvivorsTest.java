package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.DogFollowupPort;
import com.agilityhub.core.clubs.bookings.application.ports.TrainingStatsQuery;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.AttendanceCensusAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link InstructorCardQuery} (S10 R-10-08, R-10-09; T-10-14): an unknown dog is 404, a last
 * class carries its class's display description, and the instructor note's attachments are listed with their signed url.
 */
class InstructorCardQuerySurvivorsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    /** Monday 5 October, 18:00 Madrid: a class two days ago. */
    static final Instant LAST = Instant.parse("2026-10-05T16:00:00Z");

    final BookingContext context = mock(BookingContext.class);
    final AttendanceCensusAccess census = mock(AttendanceCensusAccess.class);
    final BookingRepository bookings = mock(BookingRepository.class);
    final AttendanceRepository attendances = mock(AttendanceRepository.class);
    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final PlanningCatalogAccess catalogs = mock(PlanningCatalogAccess.class);
    final TrainingStatsQuery trainings = mock(TrainingStatsQuery.class);
    final DogFollowupPort followup = mock(DogFollowupPort.class);
    final InstructorCardQuery query = new InstructorCardQuery(context, census, bookings, attendances, classes, catalogs, trainings, followup);

    @BeforeEach void setUp() {
        when(context.now()).thenReturn(NOW);
        when(context.zone()).thenReturn(MADRID);
    }

    @Test void T_10_14_anUnknownDogIsNotFound() {
        assertThatThrownBy(() -> query.card("dog-unknown"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test void T_10_14_aLastClassCarriesItsDescriptionAndTheNotesAttachmentsAreListed() {
        var pair = new AttendanceCensusAccess.Pair("dog-duna", "Duna", null, "Border collie", "FEMALE", LocalDate.parse("2020-03-01"), null, "ACTIVE",
                null, null, "member-laura", "Laura", "Laura Serra", "FEMALE");
        when(census.pair("dog-duna")).thenReturn(Optional.of(pair));
        when(census.memberStatus("member-laura")).thenReturn(new AttendanceCensusAccess.Status("ACTIVE", null));
        // One ACTIVE booking of Monday's class, not marked yet (PENDING).
        when(bookings.forDogs(eq(List.of("dog-duna")), any(), isNull(), any())).thenReturn(List.of(booking()));
        when(classes.labels(anyCollection(), any(Locale.class)))
                .thenReturn(Map.of("class-a", new ClassSessionBookingAccess.Labels("Grup A", "Pista 1", "#1E6091", List.of(), "Neus", "ring-1")));
        // TASKS on, the follow-up port served (E6-T03): a note with one PDF attachment, no tasks, no observations.
        when(context.enabled(Module.TASKS)).thenReturn(true);
        when(followup.available()).thenReturn(true);
        when(followup.instructorNote("dog-duna")).thenReturn(Optional.of(new DogFollowupPort.Note("Treballar el contacte a la palanca", NOW.minusSeconds(86_400),
                List.of(new DogFollowupPort.NoteAttachment("att-1", "pla.pdf", "application/pdf", "https://files.example.test/att-1")))));
        when(followup.tasks("dog-duna")).thenReturn(new DogFollowupPort.Tasks(0, 0, null));

        var card = query.card("dog-duna");

        @SuppressWarnings("unchecked") var lastClasses = (List<Map<String, Object>>) card.get("lastClasses");
        assertThat(lastClasses).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("bookingId", "booking-1").containsEntry("displayDescription", "Grup A");
            assertThat(row).containsEntry("ringName", "Pista 1").containsEntry("instructorName", "Neus");
        });
        @SuppressWarnings("unchecked") var note = (Map<String, Object>) card.get("instructorNote");
        assertThat(note.get("attachments")).isEqualTo(List.of(
                Map.of("id", "att-1", "name", "pla.pdf", "mimeType", "application/pdf", "url", "https://files.example.test/att-1")));
    }

    static Booking booking() {
        var booked = LAST.minusSeconds(3 * 86_400);
        return new Booking("booking-1", "club-a", "class-a", "dog-duna", "member-laura", BookingState.ACTIVE, BookingOrigin.APP, booked,
                new Booking.Actor("account-laura", null, "Laura"), LAST, LAST.plusSeconds(3600), "2026-10-04",
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null,
                0L, booked, "account-laura", booked, "account-laura");
    }
}
