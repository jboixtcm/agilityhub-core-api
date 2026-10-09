package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import com.agilityhub.core.shared.application.lists.ListEngine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link AttendanceListQuery} (S10 §6 `GET /attendances`, T-10-21): the dataset labels a facet
 * value with its text, and a row names who marked it. Every stored attendance has `markedBy`: its only writer,
 * `AttendanceSheetService#apply`, builds the `Marker` on each mark; facet values are never null
 * (`MongoListRepository#facets` matches `$ne: null`).
 */
class AttendanceListQuerySurvivorsTest {
    static final Instant MARKED = Instant.parse("2026-10-05T08:10:00Z");

    final AttendanceRepository attendances = mock(AttendanceRepository.class);
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final AttendanceListQuery query = new AttendanceListQuery(attendances, census);

    @Test void T_10_21_theDatasetLabelsAFacetValueWithItsText() {
        var label = query.dataset("attendances").label();

        assertThat(label.apply("state", "PRESENT")).isEqualTo("PRESENT");
    }

    @Test void T_10_21_aRowNamesWhoMarkedIt() {
        var engine = mock(ListEngine.class);
        var params = new LinkedMultiValueMap<String, String>();
        when(engine.list("attendances", params)).thenReturn(new ListPage<Map<String, Object>>(
                List.of(Map.<String, Object>of("id", "attendance-1")), 0, 20, 1L, 1, List.of()));
        when(attendances.byIds(List.of("attendance-1"))).thenReturn(List.of(attendance("attendance-1")));

        var page = query.list(engine, params);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst()).containsEntry("id", "attendance-1").containsEntry("markedByName", "Neus");
    }

    private static Attendance attendance(String id) {
        return new Attendance(id, "club-a", "booking-" + id, "class-1", LocalDate.parse("2026-10-05"), Instant.parse("2026-10-05T07:00:00Z"),
                Instant.parse("2026-10-05T08:00:00Z"), "dog-duna", "member-laura", AttendanceState.PRESENT, MARKED,
                new Attendance.Marker("account-neus", ActorRole.INSTRUCTOR, "Neus"), null, null,
                List.of(new Attendance.Change(AttendanceState.PRESENT, MARKED, "account-neus")), 0L, MARKED, MARKED);
    }
}
