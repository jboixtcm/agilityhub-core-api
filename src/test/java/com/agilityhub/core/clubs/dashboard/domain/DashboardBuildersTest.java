package com.agilityhub.core.clubs.dashboard.domain;

import com.agilityhub.core.clubs.dashboard.application.DashboardData.*;
import com.agilityhub.core.clubs.dashboard.application.ports.*;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DashboardBuildersTest {
    final DashboardPeriod period = new DashboardPeriod(LocalDate.of(2026, 8, 10), ZoneId.of("Europe/Madrid"));
    static LocalizedText label(String value) { return LocalizedText.fromJson(Map.of("ca", value, "en", "English " + value)); }
    @Test void T_14_01_classOccupancyExcludesDraftCancelledAndOutsideWeekAndRoundsHalfUp() {
        var rows = List.of(session("ACTIVE", 5, 3, 1), session("FINISHED", 5, 4, 3), session("CANCELLED", 4, 2, 0),
                session("DRAFT", 5, 0, 0), new ClassOccupancyQuery.Session(period.until(), "ACTIVE", 10, 10, 10),
                new ClassOccupancyQuery.Session(period.from().minusNanos(1), "ACTIVE", 10, 10, 10));
        assertThat(KpiBuilder.occupancy(rows, period, true)).isEqualTo(new Occupancy(70.0, 7, 10, 4));
        assertThat(KpiBuilder.occupancy(rows, period, false).waitingTotal()).isZero();
        assertThat(KpiBuilder.occupancy(List.of(), period, true)).isEqualTo(new Occupancy(null, 0, 0, 0));
        assertThat(KpiBuilder.occupancy(List.of(session("ACTIVE", 8, 1, 0)), period, true).percent()).isEqualTo(13.0);
        assertThat(KpiBuilder.occupancy(List.of(session("ACTIVE", 163, 142, 0)), period, true).percent()).isEqualTo(87.0);
    }
    ClassOccupancyQuery.Session session(String state, int capacity, int booked, int waiting) {
        return new ClassOccupancyQuery.Session(period.from(), state, capacity, booked, waiting);
    }
    @Test void T_14_02_isoWeekMonthAndActivityBoundariesUseClubLocalTimeIncludingDst() {
        Instant instant = Instant.parse("2026-08-10T00:30:00Z");
        var madrid = new DashboardPeriod(instant.atZone(period.zone()).toLocalDate(), period.zone());
        var buenosAires = ZoneId.of("America/Argentina/Buenos_Aires");
        var argentina = new DashboardPeriod(instant.atZone(buenosAires).toLocalDate(), buenosAires);
        assertThat(madrid.weekStart()).isEqualTo("2026-08-10"); assertThat(madrid.weekEnd()).isEqualTo("2026-08-16");
        assertThat(argentina.weekStart()).isEqualTo("2026-08-03"); assertThat(argentina.weekEnd()).isEqualTo("2026-08-09");
        assertThat(madrid.monthFrom()).isEqualTo("2026-07-31T22:00:00Z");
        assertThat(madrid.monthUntil()).isEqualTo("2026-08-31T22:00:00Z");
        assertThat(argentina.monthFrom()).isEqualTo("2026-08-01T03:00:00Z");
        assertThat(madrid.activityFrom(2)).isEqualTo("2026-08-02T22:00:00Z");
        assertThat(madrid.activityFrom(1)).isEqualTo(madrid.from());
        for (var entry : Map.of(LocalDate.of(2026, 3, 29), 167L, LocalDate.of(2026, 10, 25), 169L).entrySet()) {
            var dst = new DashboardPeriod(entry.getKey(), period.zone());
            assertThat(Duration.between(dst.from(), dst.until()).toHours()).isEqualTo(entry.getValue());
        }
    }
    @Test void T_14_03_trainingCountsActiveBookingsAndDistinctMembersInLocalWeek() {
        var rows = List.of(new TrainingBookingsQuery.Booking("laura", period.from(), "ACTIVE"),
                new TrainingBookingsQuery.Booking("laura", period.from().plusSeconds(1), "ACTIVE"),
                new TrainingBookingsQuery.Booking("pau", period.until().minusNanos(1), "ACTIVE"),
                new TrainingBookingsQuery.Booking("other", period.from(), "CANCELLED"),
                new TrainingBookingsQuery.Booking("other", period.until(), "ACTIVE"),
                new TrainingBookingsQuery.Booking("other", period.from().minusSeconds(1), "ACTIVE"));
        assertThat(KpiBuilder.training(rows, period)).isEqualTo(new Training(3, 2));
    }
    PendingSource pending(String first, String last, int days, boolean add, boolean warnings) {
        return new PendingSource(first, first, last, add, List.of(new PendingDog("Kiwi", "Whippet", add)),
                warnings ? null : label("Abonat"), warnings ? "SEPA_DD" : "MANUAL", period.today().minusDays(days).atTime(23, 59).atZone(period.zone()).toInstant(),
                !warnings, !warnings, warnings, warnings, warnings, warnings);
    }
    @Test void T_14_04_pendingCalendarDaysWarningsAddDogAndOptionalBillingFields() {
        var sources = List.of(pending("Marta", "Riera", 1, false, false), pending("Pau", "Camps", 4, true, false), pending("Nuria", "Torre", 9, false, true));
        var result = PendingSignupBuilder.build(sources, period, 7, true, true, "ca", "ca");
        assertThat(result.kpi()).isEqualTo(new PendingKpi(3, 1, 7));
        assertThat(result.card().items()).extracting(PendingItem::pendingDays).containsExactly(9, 4, 1);
        assertThat(result.card().items().get(1).dogs().getFirst().isAddDog()).isTrue();
        assertThat(result.card().items().get(2).shortName()).isEqualTo("Marta R.");
        assertThat(result.card().items().getFirst().planName()).isEqualTo("—");
        assertThat(result.card().items().getFirst().warnings()).hasSize(6);
        assertThat(result.card().items().get(2).warnings()).isEmpty();
        assertThat(PendingSignupBuilder.build(sources, period, 4, true, false, "en", "ca").kpi().olderThanWarn()).isEqualTo(1);
        assertThat(PendingSignupBuilder.build(sources, period, 2, true, false, "en", "ca").kpi().olderThanWarn()).isEqualTo(2);
        var minimal = PendingSignupBuilder.build(sources, period, 7, false, false, "ca", "ca");
        assertThat(minimal.card().items()).allSatisfy(item -> { assertThat(item.paymentMethodType()).isNull(); assertThat(item.warnings()).isNull(); });
        var missing = List.of(pending("First", null, -1, false, false), pending("Second", " ", 0, false, false));
        assertThat(PendingSignupBuilder.build(missing, period, 0, true, true, "en", "ca").card().items())
                .allSatisfy(item -> { assertThat(item.pendingDays()).isZero(); assertThat(item.shortName()).doesNotContain("."); assertThat(item.planName()).isEqualTo("English Abonat"); });
    }
    ClassSessionsQuery.Session risk(String id, int days, String time, String state, String reason, boolean exempt, int booked, boolean notice, List<Notified> notified) {
        return new ClassSessionsQuery.Session(id, period.today().plusDays(days), LocalTime.parse(time), label(id), label("Ring"), state, reason, exempt, booked, notice,
                state.equals("CANCELLED") ? notified : List.of(), notice ? notified : List.of());
    }
    @Test void T_14_05_riskRowsMatchMockupWindowPolicyAndNotificationSnapshots() {
        var laura = List.of(new Notified("Laura", "FEMALE", "Duna")); var pau = List.of(new Notified("Pau", "MALE", "Blat"));
        var rows = List.of(risk("future", 2, "09:30", "ACTIVE", null, false, 0, false, List.of()),
                risk("zero", 0, "09:30", "CANCELLED", "RISK_REVIEW", false, 0, false, List.of()),
                risk("laura", 0, "17:40", "CANCELLED", "RISK_REVIEW", false, 1, false, laura),
                risk("pau", 1, "20:00", "ACTIVE", null, false, 1, true, pau),
                risk("manual", 0, "12:00", "CANCELLED", "CLUB_MANUAL", false, 0, false, List.of()),
                risk("deleted", 0, "12:00", "CANCELLED", "DELETED", false, 0, false, List.of()),
                risk("exempt", 0, "12:00", "ACTIVE", null, true, 0, false, List.of()),
                risk("outside", 3, "12:00", "ACTIVE", null, false, 0, false, List.of()),
                risk("past", -1, "12:00", "ACTIVE", null, false, 0, false, List.of()),
                risk("draft", 1, "12:00", "DRAFT", null, false, 0, false, List.of()),
                risk("safe", 1, "12:00", "ACTIVE", null, false, 2, false, List.of()));
        var builder = new RiskCardBuilder(s -> s.booked() < 2);
        var result = builder.build(rows, period, 2, LocalTime.of(7, 30), true, "ca", "ca");
        assertThat(result.count()).isEqualTo(4);
        assertThat(result.items()).extracting(RiskItem::status).containsExactly(RiskStatus.CANCELLED, RiskStatus.CANCELLED, RiskStatus.AT_RISK, RiskStatus.WILL_CANCEL);
        assertThat(result.items().get(1).notified()).isEqualTo(laura); assertThat(result.items().get(2).notified()).isEqualTo(pau);
        assertThat(result.items().getLast().reviewAt()).isEqualTo("2026-08-12T05:30:00Z");
        assertThat(builder.build(rows, period, 2, LocalTime.of(7, 30), false, "ca", "ca").items().getLast().status()).isEqualTo(RiskStatus.PENDING_DECISION);
        assertThat(builder.build(List.of(risk("unnotified", 1, "12:00", "ACTIVE", null, false, 1, false, List.of())), period, 2, LocalTime.of(7,30), true, "en", "ca")
                .items().getFirst().status()).isEqualTo(RiskStatus.AT_RISK);
    }
    @Test void T_14_06_dogsOfInactiveLevelsAndMissingLevelsBelongToOthersIncludingTotal() {
        var levels = List.of(new LevelSource("F", "F", label("F"), "#000000", 2, false),
                new LevelSource("Z", "Z", label("Z"), "#000000", 5, true), new LevelSource("A", "A", label("A"), "#000000", 1, true));
        var result = DogActivityBuilder.build(levels, List.of(new DogCount("A", 4, 3), new DogCount("F", 2, 2)), 2, "ca", "ca");
        assertThat(result.totalActiveDogs()).isEqualTo(6); assertThat(result.others()).isEqualTo(2);
        assertThat(result.levels()).extracting(Level::code).containsExactly("A", "Z");
        assertThat(result.levels().getFirst().total()).isEqualTo(4); assertThat(result.levels().getFirst().withRecentBooking()).isEqualTo(3);
        assertThat(result.levels().getLast().total()).isZero();
        assertThat(DogActivityBuilder.build(List.of(), List.of(new DogCount(null, 1, 0), new DogCount("missing", 1, 0)), 1, "ca", "ca").others()).isEqualTo(2);
    }
}
