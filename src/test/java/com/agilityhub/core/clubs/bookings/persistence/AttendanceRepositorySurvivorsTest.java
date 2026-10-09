package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link AttendanceRepository} (S10 §3): the reads and the compare-and-set writes answer what
 * Mongo returned, the N-19 «avís ja enviat» mark is set once, and a class refresh reports how many rows it changed.
 */
class AttendanceRepositorySurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final MongoTemplate mongo = mock(MongoTemplate.class);
    final AttendanceRepository repository = new AttendanceRepository(mongo);

    @BeforeEach void openTenant() { TenantContext.clear(); TenantContext.open(CLUB); }
    @AfterEach void closeTenant() { TenantContext.clear(); }

    @Test void T_10_14_theCardReadsTheDogsAttendancesSinceTheWindowStart() {
        var attendance = attendance("attendance-1");
        when(mongo.find(any(Query.class), eq(Attendance.class))).thenReturn(List.of(attendance));

        assertThat(repository.findByDogSince("dog-1", NOW)).containsExactly(attendance);
    }

    @Test void T_10_12_aFirstMarkReturnsTheInsertedAttendance() {
        var next = attendance("attendance-1");
        var stored = attendance("attendance-1");
        when(mongo.insert(next)).thenReturn(stored);

        assertThat(repository.upsert(next, null)).isSameAs(stored);
    }

    @Test void T_10_12_aLaterMarkReturnsTheReplacedAttendance() {
        var next = attendance("attendance-1");
        var saved = attendance("attendance-1");
        when(mongo.findAndReplace(any(Query.class), any(Attendance.class), any(FindAndReplaceOptions.class))).thenReturn(saved);

        assertThat(repository.upsert(next, 2L)).isSameAs(saved);
    }

    @Test void T_10_08_theNoticeSentMarkIsTrueOnlyWhenItWasWritten() {
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(Attendance.class))).thenReturn(modified(0), modified(1));

        assertThat(repository.noticeSent("attendance-1", "event-1", NOW)).isFalse();
        assertThat(repository.noticeSent("attendance-1", "event-1", NOW)).isTrue();
    }

    @Test void T_10_10_aClassRefreshReportsHowManyAttendancesChanged() {
        // The filter only matches stale rows and the update always bumps `version`, so every matched row is modified.
        when(mongo.updateMulti(any(Query.class), any(UpdateDefinition.class), eq(Attendance.class))).thenReturn(UpdateResult.acknowledged(2, 2L, null));

        assertThat(repository.refreshClass("class-1", LocalDate.parse("2026-10-05"), NOW, NOW.plusSeconds(3600))).isEqualTo(2L);
    }

    static Attendance attendance(String id) {
        var attendance = mock(Attendance.class);
        when(attendance.id()).thenReturn(id);
        when(attendance.clubId()).thenReturn(CLUB);
        return attendance;
    }

    static UpdateResult modified(long count) { return UpdateResult.acknowledged(count, count, null); }
}
