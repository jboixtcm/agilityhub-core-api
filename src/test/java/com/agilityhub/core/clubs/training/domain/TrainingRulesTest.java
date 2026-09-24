package com.agilityhub.core.clubs.training.domain;

import com.agilityhub.core.clubs.bookings.domain.BookingWeeks;
import com.agilityhub.core.support.MockClock;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** S09 domain rules with a mutable clock in Europe/Madrid and America/Argentina/Buenos_Aires (the examples of R-09-01…R-09-10). */
class TrainingRulesTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid"), BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    static final TrainingGrid.Hours HOURS = new TrainingGrid.Hours(LocalTime.of(7, 0), LocalTime.of(22, 0));
    final MockClock clock = new MockClock(Instant.parse("2026-10-05T08:00:00Z"));
    static Instant local(String dateTime, ZoneId zone) { return LocalDateTime.parse(dateTime).atZone(zone).toInstant(); }
    static Instant madrid(String dateTime) { return local(dateTime, MADRID); }
    static TrainingGrid.Slot slot(String dateTime) {
        var start = madrid(dateTime); return new TrainingGrid.Slot(LocalDate.parse(dateTime.substring(0, 10)), start, start.plusSeconds(1800));
    }

    @Test void T_09_01_theRightFollowsTheOverrideThenTheLevelAndOnlyTheOverrideWithoutLevels() {
        // Rock (D grants, no override), Kira (B, override true), Toby (B, no override), Nala (E grants, override false).
        assertThat(TrainingRules.canFreeTrain(true, null, true, true)).isTrue();
        assertThat(TrainingRules.canFreeTrain(true, true, true, false)).isTrue();
        assertThat(TrainingRules.canFreeTrain(true, null, true, false)).isFalse();
        assertThat(TrainingRules.canFreeTrain(true, false, true, true)).isFalse();
        assertThat(TrainingRules.canFreeTrain(false, true, true, true)).as("an inactive dog never trains").isFalse();
        // levels.enabled = false: only Kira's explicit override grants it.
        assertThat(List.of(TrainingRules.canFreeTrain(true, null, false, true), TrainingRules.canFreeTrain(true, true, false, false),
                TrainingRules.canFreeTrain(true, null, false, false), TrainingRules.canFreeTrain(true, false, false, true))).containsExactly(false, true, false, false);
        assertThat(TrainingRules.rightSource(null)).isEqualTo(TrainingRules.RightSource.LEVEL);
        assertThat(TrainingRules.rightSource(true)).isEqualTo(TrainingRules.RightSource.MANUAL);
    }

    @Test void T_09_03_capacityDecidesWhenASeatedRingIsBooked() {
        var slot = slot("2026-10-06T08:30"); var central = new TrainingGrid.Ring("cen", 2);
        var one = List.of(new TrainingGrid.Seat("b1", "cen", "rock", slot.startsAt(), slot.endsAt(), 0));
        var cell = TrainingGrid.cell(central, slot, List.of(), List.of(), one, "kira");
        assertThat(cell.state()).isEqualTo(SlotState.FREE); assertThat(cell.capacity()).isEqualTo(2); assertThat(cell.freeSeat()).isEqualTo(1);
        var two = List.of(one.getFirst(), new TrainingGrid.Seat("b2", "cen", "toby", slot.startsAt(), slot.endsAt(), 1));
        assertThat(TrainingGrid.cell(central, slot, List.of(), List.of(), two, "kira").state()).isEqualTo(SlotState.BOOKED);
        assertThat(TrainingGrid.cell(new TrainingGrid.Ring("mun", 1), slot, List.of(), List.of(), one, null).state()).as("another ring is not touched").isEqualTo(SlotState.FREE);
    }

    @Test void T_09_04_openingHoursGiveThirtySlotsAndAHolidayNone() {
        var monday = TrainingGrid.slots(LocalDate.parse("2026-10-05"), HOURS, false, MADRID, 30);
        assertThat(monday).hasSize(30);
        assertThat(monday.getFirst().startsAt()).isEqualTo(Instant.parse("2026-10-05T05:00:00Z"));
        assertThat(monday.getLast().endsAt()).isEqualTo(Instant.parse("2026-10-05T20:00:00Z"));
        assertThat(monday.getFirst().slotId("r-mun")).isEqualTo("r-mun_2026-10-05T05:00:00Z");
        assertThat(TrainingGrid.slots(LocalDate.parse("2026-10-12"), HOURS, true, MADRID, 30)).isEmpty();
        assertThat(TrainingGrid.slots(LocalDate.parse("2026-10-12"), null, false, MADRID, 30)).as("no opening hours = closed").isEmpty();
    }

    @Test void T_09_05_theDstChangeDayKeepsThirtySlotsInBothZones() {
        var madrid = TrainingGrid.slots(LocalDate.parse("2026-10-25"), HOURS, false, MADRID, 30);
        assertThat(madrid).hasSize(30); assertThat(madrid.getFirst().startsAt()).isEqualTo(Instant.parse("2026-10-25T06:00:00Z"));
        var buenosAires = TrainingGrid.slots(LocalDate.parse("2026-10-25"), HOURS, false, BUENOS_AIRES, 30);
        assertThat(buenosAires).hasSize(30); assertThat(buenosAires.getFirst().startsAt()).isEqualTo(Instant.parse("2026-10-25T10:00:00Z"));
        // A club open across the spring gap (02:00–03:00 does not exist on 29-03-2026) never fails nor duplicates a slot.
        var night = TrainingGrid.slots(LocalDate.parse("2026-03-29"), new TrainingGrid.Hours(LocalTime.of(1, 0), LocalTime.of(4, 0)), false, MADRID, 30);
        assertThat(night).extracting(TrainingGrid.Slot::startsAt).doesNotHaveDuplicates().isSorted();
        assertThat(night.get(2).startsAt()).as("02:00 shifts forward to 03:00 CEST").isEqualTo(Instant.parse("2026-03-29T01:00:00Z"));
        // Autumn overlap: an ambiguous local time takes its first occurrence (CEST).
        var autumn = TrainingGrid.slots(LocalDate.parse("2026-10-25"), new TrainingGrid.Hours(LocalTime.of(2, 0), LocalTime.of(3, 0)), false, MADRID, 30);
        assertThat(autumn.getFirst().startsAt()).isEqualTo(Instant.parse("2026-10-25T00:00:00Z"));
    }

    @Test void T_09_06_classesBlocksAndBookingsGiveThePriorityStates() {
        var classes = List.of(new TrainingGrid.Busy("draft", "mun", madrid("2026-10-06T08:30"), madrid("2026-10-06T09:40")));
        var blocks = List.of(new TrainingGrid.Busy("block", "car", madrid("2026-10-06T16:00"), madrid("2026-10-06T18:00")));
        var seats = List.of(new TrainingGrid.Seat("tb-rock", "cen", "rock", madrid("2026-10-06T09:30"), madrid("2026-10-06T10:00"), 0));
        var mun = new TrainingGrid.Ring("mun", 1); var car = new TrainingGrid.Ring("car", 1); var cen = new TrainingGrid.Ring("cen", 1);
        for (String time : List.of("08:30", "09:00", "09:30")) {
            var cell = TrainingGrid.cell(mun, slot("2026-10-06T" + time), classes, blocks, seats, "rock");
            assertThat(cell.state()).isEqualTo(SlotState.BLOCKED); assertThat(cell.reason()).isEqualTo(SlotReason.CLASS);
        }
        assertThat(TrainingGrid.cell(mun, slot("2026-10-06T10:00"), classes, blocks, seats, "rock").state()).isEqualTo(SlotState.FREE);
        assertThat(TrainingGrid.cell(mun, slot("2026-10-06T08:00"), classes, blocks, seats, "rock").state()).isEqualTo(SlotState.FREE);
        for (String time : List.of("16:00", "16:30", "17:00", "17:30")) {
            assertThat(TrainingGrid.cell(car, slot("2026-10-06T" + time), classes, blocks, seats, "rock").reason()).isEqualTo(SlotReason.RING_BLOCK);
        }
        assertThat(TrainingGrid.cell(car, slot("2026-10-06T18:00"), classes, blocks, seats, "rock").state()).isEqualTo(SlotState.FREE);
        var own = TrainingGrid.cell(cen, slot("2026-10-06T09:30"), classes, blocks, seats, "rock");
        assertThat(own.state()).isEqualTo(SlotState.BOOKED); assertThat(own.reason()).isEqualTo(SlotReason.OWN_TRAINING); assertThat(own.ownBookingId()).isEqualTo("tb-rock");
        assertThat(TrainingGrid.cell(cen, slot("2026-10-06T09:30"), classes, blocks, seats, "kira").reason()).isEqualTo(SlotReason.TRAINING);
        // A class wins over a block and a booking on the same ring and slot.
        var all = TrainingGrid.cell(cen, slot("2026-10-06T09:30"), List.of(new TrainingGrid.Busy("c", "cen", madrid("2026-10-06T09:00"), madrid("2026-10-06T10:00"))),
                List.of(new TrainingGrid.Busy("b", "cen", madrid("2026-10-06T09:30"), madrid("2026-10-06T10:00"))), seats, "rock");
        assertThat(all.reason()).isEqualTo(SlotReason.CLASS); assertThat(all.seats()).hasSize(1); assertThat(all.blocks()).hasSize(1);
    }

    @Test void T_09_07_theWindowOpensAWholeDayAtLocalMidnight() {
        clock.setInstant(madrid("2026-10-05T23:59"));
        var friday = madrid("2026-10-09T07:00"); var date = LocalDate.parse("2026-10-09");
        assertThat(TrainingRules.bookable(true, friday, clock.instant(), date, clock.instant().atZone(MADRID).toLocalDate(), 3)).isFalse();
        assertThat(TrainingRules.inWindow(LocalDate.parse("2026-10-08"), clock.instant().atZone(MADRID).toLocalDate(), 3)).isTrue();
        clock.setInstant(madrid("2026-10-06T00:00"));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-05T22:00:00Z"));
        assertThat(TrainingRules.bookable(true, friday, clock.instant(), date, clock.instant().atZone(MADRID).toLocalDate(), 3)).isTrue();
        assertThat(TrainingRules.inWindow(LocalDate.parse("2026-10-05"), clock.instant().atZone(MADRID).toLocalDate(), 3)).as("yesterday").isFalse();
        // Buenos Aires: the same instant is still the 5th there, so Friday is not open yet.
        assertThat(TrainingRules.inWindow(date, clock.instant().atZone(BUENOS_AIRES).toLocalDate(), 3)).isFalse();
    }

    static TrainingWeek.Counted counted(String id, String startsAt, TrainingBookingState state, Instant weekStart) {
        return new TrainingWeek.Counted(id, "cen", madrid(startsAt), state, weekStart);
    }
    @Test void T_09_08_theCounterIsBySessionWeekCountingDoneAndNotCancelledBookings() {
        var weeks = new BookingWeeks(new BookingWeeks.Opening(DayOfWeek.SUNDAY, LocalTime.of(20, 0)), MADRID);
        var w = weeks.week(madrid("2026-10-06T07:30")).start(); var next = weeks.week(madrid("2026-10-12T08:00")).start();
        assertThat(w).isEqualTo(Instant.parse("2026-10-04T18:00:00Z")); assertThat(next).isEqualTo(Instant.parse("2026-10-11T18:00:00Z"));
        clock.setInstant(madrid("2026-10-07T12:00"));
        var rock = List.of(counted("done", "2026-10-06T07:30", TrainingBookingState.ACTIVE, w), counted("thu", "2026-10-08T20:30", TrainingBookingState.ACTIVE, w),
                counted("cancelled", "2026-10-09T09:00", TrainingBookingState.CANCELLED, w), counted("club", "2026-10-09T10:00", TrainingBookingState.CANCELLED_BY_CLUB, w));
        assertThat(TrainingWeek.counted(rock, w)).extracting(TrainingWeek.Counted::id).containsExactly("done", "thu");
        assertThat(TrainingWeek.counted(rock, next)).isEmpty();
        // Booked on Friday for Monday 12-10: it counts in the next week (by session date), 1/3 there.
        var withMonday = new ArrayList<>(rock); withMonday.add(counted("mon", "2026-10-12T08:00", TrainingBookingState.ACTIVE, next));
        assertThat(TrainingWeek.counted(withMonday, next)).hasSize(1); assertThat(TrainingWeek.counted(withMonday, w)).hasSize(2);
    }

    @Test void T_09_09_theTrainingWeekStartsAtTheLastLocalOpeningAcrossDst() {
        var weeks = new BookingWeeks(new BookingWeeks.Opening(DayOfWeek.SUNDAY, LocalTime.of(20, 0)), MADRID);
        assertThat(weeks.week(madrid("2026-10-25T19:30")).start()).isEqualTo(Instant.parse("2026-10-18T18:00:00Z"));
        assertThat(weeks.week(madrid("2026-10-25T20:00")).start()).isEqualTo(Instant.parse("2026-10-25T19:00:00Z"));
        assertThat(Duration.between(Instant.parse("2026-10-18T18:00:00Z"), weeks.week(madrid("2026-10-25T19:30")).end())).isEqualTo(Duration.ofHours(169));
        var buenosAires = new BookingWeeks(new BookingWeeks.Opening(DayOfWeek.SUNDAY, LocalTime.of(20, 0)), BUENOS_AIRES);
        assertThat(buenosAires.week(local("2026-10-25T20:00", BUENOS_AIRES)).start()).isEqualTo(Instant.parse("2026-10-25T23:00:00Z"));
    }

    @Test void T_09_10_withTheMemberUnitTheLimitListsOnlyTheFutureBookingsInsideTheThreshold() {
        var w = Instant.parse("2026-10-04T18:00:00Z");
        clock.setInstant(madrid("2026-10-08T19:00"));
        // Maria: Rock ×2 (one done, one at 20:00 today — inside the 120 min threshold) and Kira ×1 (Saturday).
        var maria = List.of(counted("rock-done", "2026-10-06T07:30", TrainingBookingState.ACTIVE, w), counted("rock-late", "2026-10-08T20:00", TrainingBookingState.ACTIVE, w),
                counted("kira-sat", "2026-10-10T09:00", TrainingBookingState.ACTIVE, w));
        var week = TrainingWeek.counted(maria, w);
        assertThat(week).hasSize(3).hasSizeGreaterThanOrEqualTo(3);
        assertThat(TrainingWeek.cancellable(week, clock.instant(), 120)).extracting(TrainingWeek.Counted::id).containsExactly("kira-sat");
    }

    @Test void T_09_11_anyRingPicksTheFirstFreeInCatalogOrder() {
        var slot = slot("2026-10-05T08:30"); var cells = new LinkedHashMap<String, TrainingGrid.Cell>();
        for (String ring : List.of("mun", "cen", "car")) { cells.put(ring, TrainingGrid.cell(new TrainingGrid.Ring(ring, 1), slot, List.of(), List.of(), List.of(), null)); }
        assertThat(TrainingWeek.firstFree(cells)).contains("mun");
        cells.put("mun", TrainingGrid.cell(new TrainingGrid.Ring("mun", 1), slot, List.of(), List.of(),
                List.of(new TrainingGrid.Seat("x", "mun", "other", slot.startsAt(), slot.endsAt(), 0)), null));
        assertThat(TrainingWeek.firstFree(cells)).contains("cen");
        cells.replaceAll((ring, cell) -> TrainingGrid.cell(new TrainingGrid.Ring(ring, 1), slot, List.of(new TrainingGrid.Busy("c", ring, slot.startsAt(), slot.endsAt())), List.of(), List.of(), null));
        assertThat(TrainingWeek.firstFree(cells)).isEmpty();
    }

    @Test void T_09_12_theCancellationThresholdIsInstantArithmetic() {
        var slot = madrid("2026-10-25T08:00"); assertThat(slot).isEqualTo(Instant.parse("2026-10-25T07:00:00Z"));
        assertThat(TrainingRules.cancellableUntil(slot, 120)).isEqualTo(Instant.parse("2026-10-25T05:00:00Z"));
        clock.setInstant(madrid("2026-10-25T05:59"));
        assertThat(TrainingRules.inTime(slot, clock.instant(), 120)).isTrue();
        clock.setInstant(madrid("2026-10-25T06:00"));
        assertThat(TrainingRules.inTime(slot, clock.instant(), 120)).as("the threshold itself is still in time").isTrue();
        clock.setInstant(madrid("2026-10-25T06:01"));
        assertThat(TrainingRules.inTime(slot, clock.instant(), 120)).isFalse();
        assertThat(TrainingRules.minutesBefore(slot, clock.instant())).isEqualTo(119);
        clock.advance(Duration.ofHours(3));
        assertThat(TrainingRules.minutesBefore(slot, clock.instant())).isNegative();
    }

    @Test void T_09_14_misalignedEarlyAndHolidayStartsAreRejectedByTheGrid() {
        assertThat(TrainingGrid.placement(madrid("2026-10-05T08:20"), HOURS, false, MADRID, 30)).isEqualTo(TrainingGrid.Placement.OFF_GRID);
        assertThat(TrainingGrid.placement(madrid("2026-10-05T06:30"), HOURS, false, MADRID, 30)).isEqualTo(TrainingGrid.Placement.CLOSED);
        assertThat(TrainingGrid.placement(madrid("2026-10-05T21:45"), HOURS, false, MADRID, 30)).as("the last slot must end by closing").isEqualTo(TrainingGrid.Placement.CLOSED);
        assertThat(TrainingGrid.placement(madrid("2026-10-12T09:00"), HOURS, true, MADRID, 30)).isEqualTo(TrainingGrid.Placement.CLOSED);
        assertThat(TrainingGrid.placement(madrid("2026-10-05T08:30"), HOURS, false, MADRID, 30)).isEqualTo(TrainingGrid.Placement.ON_GRID);
        assertThat(TrainingGrid.placement(local("2026-10-05T08:30", BUENOS_AIRES), HOURS, false, BUENOS_AIRES, 30)).isEqualTo(TrainingGrid.Placement.ON_GRID);
        assertThat(TrainingGrid.placement(madrid("2026-10-05T08:30"), HOURS, false, BUENOS_AIRES, 30)).as("03:30 in Buenos Aires").isEqualTo(TrainingGrid.Placement.CLOSED);
    }

    @Test void T_09_15_aSlotThatAlreadyStartedIsNeverBookable() {
        clock.setInstant(madrid("2026-10-05T08:31")); var today = LocalDate.parse("2026-10-05");
        assertThat(TrainingRules.bookable(true, madrid("2026-10-05T08:30"), clock.instant(), today, today, 3)).isFalse();
        assertThat(TrainingRules.bookable(true, madrid("2026-10-05T09:00"), clock.instant(), today, today, 3)).isTrue();
        assertThat(TrainingRules.bookable(false, madrid("2026-10-05T09:00"), clock.instant(), today, today, 3)).as("not free").isFalse();
    }

    @Test void smsBodiesAreGsm7AndAtMost160Characters() {
        assertThat(TrainingSms.compact("Club: el club ha anul·lat l’entrenament de Rock del dilluns, 5 d’octubre de 2026 de 8:30–9:00 (Muntanya).").chars().allMatch(c -> c < 128)).isTrue();
        assertThat(TrainingSms.compact("x".repeat(200))).hasSize(160).endsWith("...");
        assertThat(TrainingGrid.Slot.slotId("r", Instant.parse("2026-10-05T06:30:00Z"))).isEqualTo("r_2026-10-05T06:30:00Z");
    }
}
