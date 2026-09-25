package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.census.application.SchedulingRecipients;
import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort;
import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort.BookingRef;
import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.platform.application.ClubConfig;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** E5-T10 (review E5-T05 #4): the D1 risk rows (S15 §6 form A) come from one batched read per collection, like T-08-47's list. */
class RiskReviewQueryTest {
    static final ZoneId ZONE = ZoneId.of("Europe/Madrid");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    private static ClassSession session(String id, LocalDate date, ClassState state, ClassSession.Risk risk, ClassSession.Cancellation cancellation) {
        var c = mock(ClassSession.class);
        when(c.id()).thenReturn(id); when(c.date()).thenReturn(date); when(c.state()).thenReturn(state); when(c.ringId()).thenReturn("ring-a");
        when(c.startsAt()).thenReturn(date.atTime(18, 0).atZone(ZONE).toInstant()); when(c.risk()).thenReturn(risk); when(c.cancellation()).thenReturn(cancellation);
        return c;
    }

    @Test void R_15_12_T_14_05_theRiskRowsOfAWindowReadBookingsMembersAndDogsOnceEach() {
        var classes = mock(ClassSessionRepository.class); var port = mock(ClassBookingsPort.class); var census = mock(SchedulingRecipients.class);
        var context = mock(PlanningContext.class); var projection = mock(SessionProjection.class); var config = mock(ClubConfig.class);
        when(context.config()).thenReturn(config); when(projection.zone()).thenReturn(ZONE);
        when(config.get("classes.riskLookaheadDays", Integer.class)).thenReturn(2); when(config.get("classes.minDogs", Integer.class)).thenReturn(3);
        when(config.get("classes.riskAutoCancelSameDay", Boolean.class)).thenReturn(true); when(config.get("classes.riskReviewTime", String.class)).thenReturn("07:30");
        when(config.get("jobs.riskReview.enabled", Boolean.class)).thenReturn(true);
        when(context.catalog()).thenReturn(new SchedulingCatalog(List.of(), List.of(new SchedulingCatalog.Resource("ring-a", "Ring A", true)), List.of()));
        // Six classes at risk (two warned registrants each), one already cancelled by the review, one full and one exempt.
        var window = new ArrayList<ClassSession>();
        var warnedIds = new ArrayList<String>();
        for (int i = 0; i < 6; i++) {
            var ids = List.of("w" + i + "a", "w" + i + "b"); warnedIds.addAll(ids);
            window.add(session("risk-" + i, TODAY.plusDays(i % 3), ClassState.ACTIVE, new ClassSession.Risk(false, ids, null, null), null));
        }
        window.add(session("cancelled", TODAY, ClassState.CANCELLED, null,
                new ClassSession.Cancellation(ClassCancellationReason.RISK_REVIEW, "auto", null, Instant.parse("2026-10-05T05:30:00Z"), 2, 0)));
        window.add(session("full", TODAY.plusDays(1), ClassState.ACTIVE, null, null));
        window.add(session("exempt", TODAY.plusDays(1), ClassState.ACTIVE, new ClassSession.Risk(true, List.of(), null, null), null));
        when(classes.startingBetween(any(), any())).thenReturn(window);
        var live = new LinkedHashMap<String, List<BookingRef>>();
        for (int i = 0; i < 6; i++) { live.put("risk-" + i, List.of(new BookingRef("w" + i + "a", "m" + i, "d" + i, false), new BookingRef("w" + i + "b", "m" + i, "e" + i, false))); }
        live.put("full", IntStream.range(0, 3).mapToObj(i -> new BookingRef("f" + i, "m0", "d0", false)).toList());
        when(port.activeBookingsByClass(any())).thenReturn(live);
        when(port.clubCancelledByClass(any())).thenReturn(Map.of("cancelled", List.of(new BookingRef("x1", "m9", "d9", false), new BookingRef("x2", "m9", "e9", false))));
        when(port.bookings(any())).thenAnswer(invocation -> invocation.<List<String>>getArgument(0).stream()
                .map(id -> new BookingRef(id, "m" + id.charAt(1), (id.endsWith("a") ? "d" : "e") + id.charAt(1), false)).toList());
        when(census.people(any())).thenAnswer(invocation -> {
            var people = new HashMap<String, SchedulingRecipients.Person>();
            invocation.<Collection<String>>getArgument(0).forEach(id -> people.put(id, new SchedulingRecipients.Person("Person " + id, "FEMALE")));
            return people;
        });
        when(census.dogs(any())).thenAnswer(invocation -> {
            var dogs = new HashMap<String, SchedulingRecipients.Dog>();
            invocation.<Collection<String>>getArgument(0).forEach(id -> dogs.put(id, new SchedulingRecipients.Dog(id, "Dog " + id, "level")));
            return dogs;
        });

        var rows = new RiskReviewQuery(classes, port, census, context, projection, Clock.fixed(Instant.parse("2026-10-05T06:00:00Z"), ZoneOffset.UTC)).rows(TODAY);

        assertThat(rows.rows()).extracting(r -> r.session().id())
                .containsExactly("risk-0", "risk-1", "risk-2", "risk-3", "risk-4", "risk-5", "cancelled");
        assertThat(rows.rows().getFirst().status()).isEqualTo("AT_RISK"); assertThat(rows.rows().getFirst().ringName()).isEqualTo("Ring A");
        assertThat(rows.rows().getFirst().notified()).extracting(RiskReviewQuery.Notified::memberName, RiskReviewQuery.Notified::dogName)
                .containsExactly(tuple("Person m0", "Dog d0"), tuple("Person m0", "Dog e0"));
        assertThat(rows.rows().getLast().status()).isEqualTo("AUTO_CANCELLED");
        assertThat(rows.rows().getLast().notified()).extracting(RiskReviewQuery.Notified::dogName).containsExactly("Dog d9", "Dog e9");
        // One `$in` per collection over the whole window: the exempt class is never read, the full one is read but not listed.
        verify(port, times(1)).activeBookingsByClass(argThat(ids -> ids.size() == 7 && ids.contains("full") && !ids.contains("exempt")));
        verify(port, times(1)).clubCancelledByClass(argThat(ids -> ids.size() == 1));
        verify(port, times(1)).bookings(argThat(ids -> ids.size() == 12 && ids.containsAll(warnedIds)));
        verify(census, times(1)).people(argThat(ids -> ids.size() == 7)); verify(census, times(1)).dogs(argThat(ids -> ids.size() == 14));
        verify(port, never()).activeBookings(anyString()); verify(port, never()).clubCancelled(anyString());
        verify(census, never()).person(any()); verify(census, never()).dog(any());
    }

    /** Statuses of today's 17:40 and tomorrow's 18:00 class, both at risk with 0 registrants and no notice, read at {@code localNow}. */
    private static Map<String, String> statuses(String localNow, boolean jobOn, boolean autoCancel) {
        return statuses(localNow, jobOn, autoCancel, LocalTime.of(17, 40), LocalTime.of(18, 0));
    }
    /** The same two classes, starting at {@code todayStart} and {@code tomorrowStart}. */
    private static Map<String, String> statuses(String localNow, boolean jobOn, boolean autoCancel, LocalTime todayStart, LocalTime tomorrowStart) {
        var classes = mock(ClassSessionRepository.class); var context = mock(PlanningContext.class); var projection = mock(SessionProjection.class);
        var config = mock(ClubConfig.class);
        when(context.config()).thenReturn(config); when(projection.zone()).thenReturn(ZONE);
        when(config.get("classes.riskLookaheadDays", Integer.class)).thenReturn(2); when(config.get("classes.minDogs", Integer.class)).thenReturn(2);
        when(config.get("classes.riskAutoCancelSameDay", Boolean.class)).thenReturn(autoCancel); when(config.get("classes.riskReviewTime", String.class)).thenReturn("07:30");
        when(config.get("jobs.riskReview.enabled", Boolean.class)).thenReturn(jobOn);
        when(context.catalog()).thenReturn(new SchedulingCatalog(List.of(), List.of(new SchedulingCatalog.Resource("ring-a", "Ring A", true)), List.of()));
        var today = session("today", TODAY, ClassState.ACTIVE, new ClassSession.Risk(false, List.of(), null, null), null);
        when(today.startsAt()).thenReturn(TODAY.atTime(todayStart).atZone(ZONE).toInstant());
        var tomorrow = session("tomorrow", TODAY.plusDays(1), ClassState.ACTIVE, new ClassSession.Risk(false, List.of(), null, null), null);
        when(tomorrow.startsAt()).thenReturn(TODAY.plusDays(1).atTime(tomorrowStart).atZone(ZONE).toInstant());
        when(classes.startingBetween(any(), any())).thenReturn(List.of(today, tomorrow));
        var now = LocalDateTime.parse(localNow).atZone(ZONE).toInstant();
        var rows = new RiskReviewQuery(classes, mock(ClassBookingsPort.class), mock(SchedulingRecipients.class), context, projection, Clock.fixed(now, ZoneOffset.UTC)).rows(TODAY);
        var result = new LinkedHashMap<String, String>(); rows.rows().forEach(r -> result.put(r.session().id(), r.status()));
        return result;
    }

    /**
     * E5-T15, ruling E37 (S15 §6, amended 24-09): `WILL_CANCEL`/`WILL_REVIEW` only while P2 will really review the class
     * (the job is on and the class's `reviewAt` is ahead); after today's review, or with the job off, the class is `AT_RISK`.
     */
    @Test void T_15_15_willCancelOrWillReviewOnlyWhileTheRiskReviewWillStillRunForTheClass() {
        assertThat(statuses("2026-10-05T10:00", true, true)).as("after today's 07:30 review").containsExactly(entry("today", "AT_RISK"), entry("tomorrow", "WILL_CANCEL"));
        assertThat(statuses("2026-10-05T07:00", true, true)).as("before today's review").containsExactly(entry("today", "WILL_CANCEL"), entry("tomorrow", "WILL_CANCEL"));
        assertThat(statuses("2026-10-05T07:30", true, true)).as("at the review instant it is running, not ahead").containsEntry("today", "AT_RISK");
        assertThat(statuses("2026-10-05T07:00", false, true)).as("the job switched off").containsExactly(entry("today", "AT_RISK"), entry("tomorrow", "AT_RISK"));
        assertThat(statuses("2026-10-05T07:00", true, false)).containsExactly(entry("today", "WILL_REVIEW"), entry("tomorrow", "WILL_REVIEW"));
        assertThat(statuses("2026-10-05T10:00", true, false)).containsExactly(entry("today", "AT_RISK"), entry("tomorrow", "WILL_REVIEW"));
        assertThat(statuses("2026-10-05T10:00", false, false)).containsExactly(entry("today", "AT_RISK"), entry("tomorrow", "AT_RISK"));
        // E5-T17 (review E5-T15 #1, S15 §6 amended 25-09): at its reviewAt P2 skips a class that has started, so a class that
        // starts before (07:00) or exactly at (07:30) its day's 07:30 review is AT_RISK, never WILL_CANCEL nor WILL_REVIEW.
        var seven = LocalTime.of(7, 0); var reviewTime = LocalTime.of(7, 30);
        assertThat(statuses("2026-10-05T06:00", true, true, seven, reviewTime)).as("07:00 and 07:30 classes, before the review")
                .containsExactly(entry("today", "AT_RISK"), entry("tomorrow", "AT_RISK"));
        assertThat(statuses("2026-10-05T06:00", true, false, seven, reviewTime)).as("the same with riskAutoCancelSameDay = false")
                .containsExactly(entry("today", "AT_RISK"), entry("tomorrow", "AT_RISK"));
        assertThat(statuses("2026-10-05T10:00", true, true, LocalTime.of(17, 40), seven)).as("tomorrow's 07:00 class, read after today's review")
                .containsExactly(entry("today", "AT_RISK"), entry("tomorrow", "AT_RISK"));
        assertThat(statuses("2026-10-05T06:00", true, true, LocalTime.of(7, 31), LocalTime.of(7, 31))).as("one minute after the review time")
                .containsExactly(entry("today", "WILL_CANCEL"), entry("tomorrow", "WILL_CANCEL"));
        assertThat(statuses("2026-10-05T06:00", true, false, LocalTime.of(7, 31), LocalTime.of(7, 31)))
                .containsExactly(entry("today", "WILL_REVIEW"), entry("tomorrow", "WILL_REVIEW"));
    }
}
