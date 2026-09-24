package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.scheduling.application.RingScheduleAccess;
import com.agilityhub.core.clubs.training.domain.TrainingGrid;
import com.agilityhub.core.platform.application.Module;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** E5-T07 round 2 · R-09-13: the ring-slot documents a write touches are exactly the training grid slots its range overlaps. */
class TrainingSlotLocksTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    final TrainingContext context = mock(TrainingContext.class);
    final RingScheduleAccess schedule = mock(RingScheduleAccess.class);
    final TrainingSlotLocks locks = new TrainingSlotLocks(context, schedule);

    static Instant local(String dateTime) { return LocalDateTime.parse(dateTime).atZone(MADRID).toInstant(); }

    TrainingSlotLocksTest() {
        when(context.enabled(Module.FREE_TRAINING)).thenReturn(true);
        when(context.zone()).thenReturn(MADRID);
        when(context.slotMinutes()).thenReturn(30);
        var hours = new EnumMap<DayOfWeek, TrainingGrid.Hours>(DayOfWeek.class);
        for (var day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)) { hours.put(day, new TrainingGrid.Hours(LocalTime.of(9, 0), LocalTime.of(11, 0))); }
        when(context.openingHours()).thenReturn(hours); // Friday–Sunday closed
        when(context.holidays()).thenReturn(Set.of(LocalDate.parse("2026-10-07"))); // a Wednesday
    }
    @SuppressWarnings("unchecked")
    List<Instant> touched() {
        var captor = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(schedule).lockRingSlots(eq("ring"), captor.capture());
        return List.copyOf((Collection<Instant>) captor.getValue());
    }

    @Test void R_09_13_aRangeTouchesEveryGridSlotItOverlapsAndNoOther() {
        locks.overlapping("ring", local("2026-10-05T09:10"), local("2026-10-05T10:00"));
        assertThat(touched()).containsExactly(local("2026-10-05T09:00"), local("2026-10-05T09:30")); // 10:00 only touches the end
    }
    @Test void R_09_13_aRangeOverSeveralDaysSkipsHolidaysAndClosedDays() {
        locks.overlapping("ring", local("2026-10-06T10:30"), local("2026-10-09T09:30"));
        assertThat(touched()).containsExactly(local("2026-10-06T10:30"), local("2026-10-08T09:00"), local("2026-10-08T09:30"),
                local("2026-10-08T10:00"), local("2026-10-08T10:30"));
    }
    @Test void R_09_13_aRangeWithoutGridSlotsOrWithoutFreeTrainingTouchesNothing() {
        locks.overlapping("ring", local("2026-10-09T09:00"), local("2026-10-09T11:00")); // Friday: closed
        when(context.enabled(Module.FREE_TRAINING)).thenReturn(false);
        locks.overlapping("ring", local("2026-10-05T09:00"), local("2026-10-05T11:00"));
        verify(schedule, never()).lockRingSlots(any(), any());
    }
    @Test void R_09_13_aRingThatStopsBeingReservableTouchesEverySlotLeftInTheBookingWindow() {
        when(context.now()).thenReturn(local("2026-10-05T10:15"));
        when(context.today()).thenReturn(LocalDate.parse("2026-10-05"));
        when(context.windowDays()).thenReturn(1);
        locks.bookable("ring");
        assertThat(touched()).containsExactly(local("2026-10-05T10:00"), local("2026-10-05T10:30"), local("2026-10-06T09:00"),
                local("2026-10-06T09:30"), local("2026-10-06T10:00"), local("2026-10-06T10:30"));
    }
}
