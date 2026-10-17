package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.agilityhub.core.shared.persistence.MongoListRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link AttendanceListQuery} (S10 §6 `GET /attendances`, T-10-21): the universal list
 * engine finds the attendance dataset under its key `attendances` (ListEngine registers each provider by its keys; an
 * unknown key is INVALID_FILTER).
 */
class AttendanceListQuerySurvivors3Test {
    @Test void T_10_21_theListEngineServesTheAttendancesUnderTheirKey() {
        var query = new AttendanceListQuery(mock(AttendanceRepository.class), mock(BookingMemberAccess.class));
        var engine = new ListEngine(List.of(query), mock(MongoListRepository.class));

        assertThat(query.keys()).containsExactly("attendances");
        assertThat(engine.dataset("attendances").collection()).isEqualTo("attendances");
    }
}
