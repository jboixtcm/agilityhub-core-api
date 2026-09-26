package com.agilityhub.core.clubs.bookings.domain;

import com.agilityhub.core.shared.application.MutableClock;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** S08 WP-08-B domain rules with a mutable clock and no Spring or Mongo. */
class BookingRulesTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid"), BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    static final BookingWeeks.Opening SUNDAY_20 = BookingWeeks.Opening.of(Map.of("dayOfWeek", "SUNDAY", "time", "20:00"));
    static final Duration LATE_240 = Duration.ofMinutes(240);
    final MutableClock clock = new com.agilityhub.core.support.MockClock(Instant.EPOCH);
    static Instant local(String dateTime, ZoneId zone) { return LocalDateTime.parse(dateTime).atZone(zone).toInstant(); }
    static BookingLimits.Counted counted(String id, String dog, String member, BookingState state, Instant startsAt) {
        return new BookingLimits.Counted(id, "class-" + id, dog, member, state, startsAt);
    }

    @Test void T_08_13_packCardStateFollowsBalanceExpiryAndTheN11Warnings() {
        var today = LocalDate.parse("2026-10-06");
        assertThat(PackCard.state(0, LocalDate.parse("2026-12-31"), today, 1, 14)).isEqualTo(PackCard.State.EMPTY);
        assertThat(PackCard.state(4, LocalDate.parse("2026-10-05"), today, 1, 14)).isEqualTo(PackCard.State.EXPIRED);
        assertThat(PackCard.state(4, LocalDate.parse("2026-10-20"), today, 1, 14)).as("expires within 14 days").isEqualTo(PackCard.State.EXPIRING);
        assertThat(PackCard.state(1, LocalDate.parse("2026-12-31"), today, 1, 14)).as("low balance").isEqualTo(PackCard.State.EXPIRING);
        assertThat(PackCard.state(4, LocalDate.parse("2026-10-21"), today, 1, 14)).isEqualTo(PackCard.State.ACTIVE);
        assertThat(PackCard.state(4, null, today, 1, 14)).as("no expiry").isEqualTo(PackCard.State.ACTIVE);
        assertThat(PackCard.state(4, today, today, 1, 14)).as("expires today").isEqualTo(PackCard.State.EXPIRING);
    }

    @Test void T_08_01_weeksOpenAtTheLocalOccurrenceAndClassesBelongToTheWeekOfTheirStart() {
        var weeks = new BookingWeeks(SUNDAY_20, MADRID);
        clock.setInstant(local("2026-10-06T10:00", MADRID));
        var w0 = weeks.week(clock.instant());
        assertThat(w0.start()).isEqualTo(local("2026-10-04T20:00", MADRID)); assertThat(w0.end()).isEqualTo(local("2026-10-11T20:00", MADRID));
        assertThat(w0.key()).isEqualTo("2026-10-04");
        assertThat(weeks.relative(local("2026-10-15T18:50", MADRID), clock.instant())).isEqualTo(RelativeWeek.NEXT);
        var tuesday = local("2026-10-20T18:50", MADRID);
        assertThat(weeks.relative(tuesday, clock.instant())).isEqualTo(RelativeWeek.LATER);
        assertThat(weeks.opensAt(weeks.week(tuesday))).isEqualTo(local("2026-10-11T20:00", MADRID));
        assertThat(weeks.relative(local("2026-10-11T21:00", MADRID), clock.instant())).isEqualTo(RelativeWeek.NEXT);
        assertThat(weeks.relative(local("2026-10-07T18:50", MADRID), clock.instant())).isEqualTo(RelativeWeek.CURRENT);
        // The table of R-08-01: one minute before the opening the class of Monday 12 is still W2; at 20:00 it is W1.
        clock.setInstant(local("2026-10-04T19:59", MADRID));
        assertThat(weeks.relative(local("2026-10-12T18:50", MADRID), clock.instant())).isEqualTo(RelativeWeek.LATER);
        clock.advance(Duration.ofMinutes(1));
        assertThat(weeks.relative(local("2026-10-12T18:50", MADRID), clock.instant())).isEqualTo(RelativeWeek.NEXT);
        assertThat(weeks.lastOpening(clock.instant())).isEqualTo(clock.instant());
        assertThat(weeks.nextBookableAt(local("2026-10-07T18:50", MADRID))).isEqualTo(local("2026-10-11T20:00", MADRID));
    }

    @Test void T_08_02_theDaylightSavingWeekLasts169HoursWithCorrectKeysAndOpenings() {
        var weeks = new BookingWeeks(SUNDAY_20, MADRID);
        var before = weeks.week(local("2026-10-20T10:00", MADRID));
        assertThat(before.key()).isEqualTo("2026-10-18");
        assertThat(Duration.between(before.start(), before.end())).isEqualTo(Duration.ofHours(169));
        assertThat(before.end()).isEqualTo(Instant.parse("2026-10-25T19:00:00Z"));
        clock.setInstant(local("2026-10-26T09:00", MADRID));
        var after = weeks.week(clock.instant());
        assertThat(after.key()).isEqualTo("2026-10-25"); assertThat(Duration.between(after.start(), after.end())).isEqualTo(Duration.ofHours(168));
        var nov3 = local("2026-11-03T19:00", MADRID);
        assertThat(weeks.relative(nov3, clock.instant())).isEqualTo(RelativeWeek.NEXT);
        assertThat(weeks.opensAt(weeks.week(nov3))).isEqualTo(Instant.parse("2026-10-25T19:00:00Z"));
        assertThat(weeks.opensAt(before)).isEqualTo(Instant.parse("2026-10-11T18:00:00Z"));
        // Spring forward (29-03-2026): 167 h.
        assertThat(Duration.between(weeks.week(local("2026-03-25T10:00", MADRID)).start(), weeks.week(local("2026-03-25T10:00", MADRID)).end())).isEqualTo(Duration.ofHours(167));
    }

    @Test void T_08_44_aMondayMidnightOpeningGivesCalendarWeeksWithoutAnyWeekdayLiteral() throws Exception {
        var weeks = new BookingWeeks(BookingWeeks.Opening.of(Map.of("dayOfWeek", "MONDAY", "time", "00:00")), MADRID);
        clock.setInstant(local("2026-10-07T12:00", MADRID));
        var w0 = weeks.week(clock.instant());
        assertThat(w0.start()).isEqualTo(local("2026-10-05T00:00", MADRID)); assertThat(w0.end()).isEqualTo(local("2026-10-12T00:00", MADRID));
        assertThat(weeks.opensAt(w0)).isEqualTo(local("2026-09-28T00:00", MADRID));
        assertThat(weeks.relative(local("2026-10-11T23:59", MADRID), clock.instant())).isEqualTo(RelativeWeek.CURRENT);
        assertThat(weeks.relative(local("2026-10-12T00:00", MADRID), clock.instant())).isEqualTo(RelativeWeek.NEXT);
        assertThat(weeks.relative(local("2026-10-19T00:00", MADRID), clock.instant())).isEqualTo(RelativeWeek.LATER);
        try (var files = Files.walk(Path.of("src/main/java/com/agilityhub/core/clubs/bookings"))) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(file)).as(file.toString()).doesNotContain("SUNDAY").doesNotContainIgnoringCase("diumenge");
            }
        }
    }

    @Test void T_08_03_theDogCounterCountsActivePendingAndLateOnly() {
        var now = local("2026-10-07T10:00", MADRID);
        var week = List.of(counted("done", "duna", "laura", BookingState.ACTIVE, local("2026-10-05T18:50", MADRID)),
                counted("late", "duna", "laura", BookingState.CANCELLED_LATE, local("2026-10-07T18:50", MADRID)),
                counted("cancelled", "duna", "laura", BookingState.CANCELLED, local("2026-10-08T18:50", MADRID)),
                counted("club", "duna", "laura", BookingState.CANCELLED_BY_CLUB, local("2026-10-09T18:50", MADRID)),
                counted("pending", "duna", "laura", BookingState.PAYMENT_PENDING, local("2026-10-10T09:00", MADRID)),
                counted("rock", "rock", "laura", BookingState.ACTIVE, local("2026-10-10T09:00", MADRID)));
        var r = BookingLimits.evaluate(week, LimitUnit.DOG, "duna", "laura", 2, now, LATE_240);
        assertThat(r.count()).isEqualTo(3); assertThat(r.reached()).isTrue(); assertThat(r.swappable()).isEmpty();
        assertThat(r.notSelectable()).extracting(b -> b.booking().bookingId() + ":" + b.reason()).containsExactly("done:DONE", "late:DONE", "pending:LATE_WINDOW");
        assertThat(r.done()).isTrue();
    }

    @Test void T_08_04_theMemberCounterSumsTheOwnersDogsWhoeverBooked() {
        var now = local("2026-10-05T09:00", MADRID);
        // Toby belongs to Joan Antoni: a booking made by Laura counts for Joan Antoni (memberId = owner), never for Laura.
        var week = List.of(counted("d", "duna", "laura", BookingState.ACTIVE, local("2026-10-05T18:50", MADRID)),
                counted("r", "rock", "laura", BookingState.ACTIVE, local("2026-10-07T18:50", MADRID)),
                counted("t", "toby", "joan", BookingState.ACTIVE, local("2026-10-08T18:50", MADRID)));
        var laura = BookingLimits.evaluate(week, LimitUnit.MEMBER, "duna", "laura", 2, now, LATE_240);
        assertThat(laura.count()).isEqualTo(2); assertThat(laura.reached()).isTrue();
        assertThat(BookingLimits.evaluate(week, LimitUnit.MEMBER, "toby", "joan", 2, now, LATE_240).count()).isEqualTo(1);
        assertThat(BookingLimits.evaluate(week, LimitUnit.DOG, "rock", "laura", 2, now, LATE_240).count()).isEqualTo(1);
        assertThat(BookingLimits.max(RelativeWeek.CURRENT, 2, 1)).isEqualTo(2); assertThat(BookingLimits.max(RelativeWeek.NEXT, 2, 1)).isEqualTo(1);
    }

    @Test void T_08_43_withMemberUnitTheOwnersOtherDogsAreSwappableAndWithDogUnitTheyAreNot() {
        var now = local("2026-10-05T09:00", MADRID);
        var week = List.of(counted("duna-mon", "duna", "laura", BookingState.ACTIVE, local("2026-10-05T18:50", MADRID)),
                counted("rock-fri", "rock", "laura", BookingState.ACTIVE, local("2026-10-09T20:00", MADRID)));
        var member = BookingLimits.evaluate(week, LimitUnit.MEMBER, "duna", "laura", 2, now, LATE_240);
        assertThat(member.reached()).isTrue(); assertThat(member.canSwap("rock-fri")).isTrue(); assertThat(member.canSwap("duna-mon")).isTrue();
        var dog = BookingLimits.evaluate(week, LimitUnit.DOG, "duna", "laura", 2, now, LATE_240);
        assertThat(dog.reached()).isFalse(); assertThat(dog.canSwap("rock-fri")).isFalse();
        // The R-08-02 table: Friday 09 inside the threshold is LATE_WINDOW, so the limit is «done».
        var inWindow = BookingLimits.evaluate(week, LimitUnit.MEMBER, "duna", "laura", 2, local("2026-10-09T18:30", MADRID), LATE_240);
        assertThat(inWindow.notSelectable()).extracting(b -> b.reason()).containsExactly(BookingLimits.Reason.DONE, BookingLimits.Reason.LATE_WINDOW);
        assertThat(inWindow.done()).isTrue();
        // Exactly at the threshold the booking is still swappable (the threshold itself is in time).
        var edge = BookingLimits.evaluate(week, LimitUnit.MEMBER, "duna", "laura", 2, local("2026-10-09T16:00", MADRID), LATE_240);
        assertThat(edge.canSwap("rock-fri")).isTrue();
        assertThat(BookingLimits.evaluate(week, LimitUnit.MEMBER, "duna", "laura", 2, local("2026-10-09T16:00:01", MADRID), LATE_240).canSwap("rock-fri")).isFalse();
    }

    @Test void T_08_05_rowStatesFollowThePriorityAndLimitDoneNeedsNoSwappable() {
        java.util.function.Function<BookableRow.Input, BookableRow.State> state = in -> BookableRow.resolve(in).state();
        assertThat(state.apply(new BookableRow.Input(BookableRow.NotBookable.BLOCKED, true, true, true, true, true, 3, 3))).isEqualTo(BookableRow.State.NOT_BOOKABLE);
        assertThat(BookableRow.resolve(new BookableRow.Input(BookableRow.NotBookable.LEAVING, true, true, true, true, true, 3, 3)).reason()).isEqualTo(BookableRow.NotBookable.LEAVING);
        assertThat(state.apply(new BookableRow.Input(null, true, true, true, true, true, 3, 3))).isEqualTo(BookableRow.State.NOT_YET_OPEN);
        assertThat(state.apply(new BookableRow.Input(null, false, true, true, true, true, 3, 3))).isEqualTo(BookableRow.State.PACK_EMPTY);
        assertThat(state.apply(new BookableRow.Input(null, false, false, true, true, true, 3, 3))).isEqualTo(BookableRow.State.WEEKLY_LIMIT_DONE);
        assertThat(state.apply(new BookableRow.Input(null, false, false, false, true, true, 3, 3))).isEqualTo(BookableRow.State.WAITLIST_FULL);
        assertThat(state.apply(new BookableRow.Input(null, false, false, false, true, true, 1, 3))).isEqualTo(BookableRow.State.WAITLIST_OPEN);
        assertThat(state.apply(new BookableRow.Input(null, false, false, false, true, false, 0, 3))).isEqualTo(BookableRow.State.FULL);
        assertThat(state.apply(new BookableRow.Input(null, false, false, false, false, true, 0, 3))).isEqualTo(BookableRow.State.BOOKABLE);
        var now = local("2026-10-08T16:00", MADRID);
        var live = List.of(counted("mon", "duna", "laura", BookingState.ACTIVE, local("2026-10-05T18:50", MADRID)),
                counted("fri", "duna", "laura", BookingState.ACTIVE, local("2026-10-09T20:00", MADRID)));
        assertThat(BookingLimits.evaluate(live, LimitUnit.DOG, "duna", "laura", 2, now, LATE_240).done()).isFalse();
        var late = List.of(counted("mon", "duna", "laura", BookingState.ACTIVE, local("2026-10-05T18:50", MADRID)),
                counted("wed", "duna", "laura", BookingState.CANCELLED_LATE, local("2026-10-07T18:50", MADRID)));
        assertThat(BookingLimits.evaluate(late, LimitUnit.DOG, "duna", "laura", 2, now, LATE_240).done()).isTrue();
    }

    @Test void T_08_06_lateIsStrictlyAfterTheThresholdAndMinutesBeforeTurnsNegative() {
        var start = local("2026-10-15T18:50", MADRID);
        assertThat(CancellationPolicy.evaluate(start, local("2026-10-15T16:50:00", MADRID), 120)).isEqualTo(new CancellationPolicy.Outcome(false, 120));
        assertThat(CancellationPolicy.evaluate(start, local("2026-10-15T16:50:01", MADRID), 120)).isEqualTo(new CancellationPolicy.Outcome(true, 119));
        assertThat(CancellationPolicy.evaluate(start, local("2026-10-15T19:05", MADRID), 120)).isEqualTo(new CancellationPolicy.Outcome(true, -15));
        // The catalog value (240, Jordi 05-09): exactly 4 h before is in time, 4 h − 1 s is late.
        assertThat(CancellationPolicy.evaluate(start, local("2026-10-15T14:50:00", MADRID), 240).late()).isFalse();
        assertThat(CancellationPolicy.evaluate(start, local("2026-10-15T14:50:01", MADRID), 240).late()).isTrue();
    }

    /** E5-T25 step 2: `Booking.cancellableInTimeUntil` is the last in-time instant of the rule above, in elapsed time. */
    @Test void T_08_06_T_08_42_theInTimeDeadlineIsTheLastInstantTheRuleCallsInTime() {
        var start = local("2026-10-15T18:50", MADRID);
        var until = CancellationPolicy.inTimeUntil(start, 240);
        assertThat(until).isEqualTo(local("2026-10-15T14:50", MADRID));
        assertThat(CancellationPolicy.evaluate(start, until, 240).late()).isFalse();
        assertThat(CancellationPolicy.evaluate(start, until.plusSeconds(1), 240).late()).isTrue();
        // Across the 25-10-2026 change: 8 h before 09:00 (08:00Z) is 00:00Z, 02:00 summer time, not 01:00 local.
        var dst = local("2026-10-25T09:00", MADRID);
        assertThat(CancellationPolicy.inTimeUntil(dst, 480)).isEqualTo(Instant.parse("2026-10-25T00:00:00Z"));
        assertThat(CancellationPolicy.inTimeUntil(dst, 480).atZone(MADRID).toLocalTime()).isEqualTo(LocalTime.parse("02:00"));
    }

    @Test void T_08_07_theWaitingListIsToldOnlyStrictlyAboveTheNoticeThreshold() {
        var start = local("2026-10-15T18:50", MADRID);
        assertThat(CancellationPolicy.notifyWaitlist(true, start, local("2026-10-15T16:49", MADRID), 30, true)).isTrue();
        assertThat(CancellationPolicy.notifyWaitlist(true, start, local("2026-10-15T18:20", MADRID), 30, true)).isFalse();
        assertThat(CancellationPolicy.notifyWaitlist(true, start, local("2026-10-15T18:25", MADRID), 30, true)).isFalse();
        assertThat(CancellationPolicy.notifyWaitlist(false, start, local("2026-10-15T16:49", MADRID), 30, true)).isFalse();
        assertThat(CancellationPolicy.notifyWaitlist(true, start, local("2026-10-15T16:49", MADRID), 30, false)).isFalse();
        assertThat(CancellationPolicy.notifyWaitlist(true, start, local("2026-10-15T18:19:59", MADRID), 30, true)).isTrue();
    }

    @Test void T_08_09_theInstructorIsVisibleFromExactly24HoursBeforeAndZeroMeansAlways() {
        var start = local("2026-10-12T18:50", MADRID);
        assertThat(InstructorVisibility.visible(local("2026-10-11T18:50", MADRID), start, 24)).isTrue();
        assertThat(InstructorVisibility.visible(local("2026-10-11T18:49", MADRID), start, 24)).isFalse();
        assertThat(InstructorVisibility.of("Estela", local("2026-10-11T18:49", MADRID), start, 24, false))
                .isEqualTo(new InstructorVisibility.View(null, local("2026-10-11T18:50", MADRID)));
        assertThat(InstructorVisibility.of("Estela", local("2026-10-11T18:49", MADRID), start, 24, true).instructorName()).isEqualTo("Estela");
        assertThat(InstructorVisibility.of("Estela", Instant.EPOCH, start, 0, false)).isEqualTo(new InstructorVisibility.View("Estela", null));
    }

    @Test void T_08_10_levelsDisabledAdmitEverythingAndAnEmptyListAdmitsAll() {
        assertThat(BookingEligibility.levelAllowed(false, null, List.of("level-d"))).isTrue();
        assertThat(BookingEligibility.levelAllowed(true, "level-c", List.of("level-b", "level-c"))).isTrue();
        assertThat(BookingEligibility.levelAllowed(true, "level-c", List.of("level-d"))).isFalse();
        assertThat(BookingEligibility.levelAllowed(true, "level-c", List.of())).isTrue();
        assertThat(BookingEligibility.levelAllowed(true, null, List.of("level-d"))).isFalse();
        var ok = input(true, List.of("level-c"), "level-c");
        assertThatCode(() -> BookingEligibility.check(ok)).doesNotThrowAnyException();
        assertCode(() -> BookingEligibility.check(input(true, List.of("level-d"), "level-c")), ErrorCode.LEVEL_NOT_ALLOWED);
        assertThatCode(() -> BookingEligibility.check(input(false, List.of("level-d"), "level-c"))).doesNotThrowAnyException();
    }

    @Test void T_08_11_anEmptyOrExpiredPackRejectsAndChecksRunInTheirFixedOrder() {
        var classDate = LocalDate.parse("2026-11-15");
        assertThat(BookingEligibility.packEmpty(new BookingEligibility.Pack(4, LocalDate.parse("2026-11-12")), classDate)).isTrue();
        assertThat(BookingEligibility.packEmpty(new BookingEligibility.Pack(4, LocalDate.parse("2026-11-12")), LocalDate.parse("2026-11-05"))).isFalse();
        assertThat(BookingEligibility.packEmpty(new BookingEligibility.Pack(0, null), LocalDate.parse("2026-11-05"))).isTrue();
        assertThat(BookingEligibility.packEmpty(new BookingEligibility.Pack(1, null), LocalDate.parse("2026-11-05"))).isFalse();
        var base = input(true, List.of(), "level-c");
        assertCode(() -> BookingEligibility.check(with(base, "pack", Optional.of(new BookingEligibility.Pack(0, null)))), ErrorCode.PACK_EMPTY);
        // Order: dog → member → block → leaving → inactivity → level → class → week → pack.
        assertCode(() -> BookingEligibility.check(with(with(base, "dogActive", false), "owner", person("PENDING", false, null))), ErrorCode.DOG_NOT_ACCESSIBLE);
        assertCode(() -> BookingEligibility.check(with(base, "booker", person("LEFT", true, "x"))), ErrorCode.MEMBER_NOT_ACTIVE);
        var blocked = assertCode(() -> BookingEligibility.check(with(base, "owner", person("ACTIVE", true, "rebut impagat"))), ErrorCode.BOOKING_BLOCKED);
        assertThat(blocked.details()).containsEntry("reason", "rebut impagat");
        var leaving = with(base, "owner", new BookingEligibility.Person("ACTIVE", false, null, LocalDate.parse("2026-10-14")));
        assertCode(() -> BookingEligibility.check(leaving), ErrorCode.CLASS_NOT_BOOKABLE);
        assertThatCode(() -> BookingEligibility.check(with(base, "owner", new BookingEligibility.Person("ACTIVE", false, null, LocalDate.parse("2026-10-15")))))
                .as("a class on the leave day itself is still bookable").doesNotThrowAnyException();
        var inactive = assertCode(() -> BookingEligibility.check(with(base, "inactivity", Optional.of(new BookingEligibility.Period(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))))), ErrorCode.INACTIVITY_PERIOD);
        assertThat(inactive.details()).containsEntry("from", "2026-10-01").containsEntry("to", "2026-10-31");
        assertCode(() -> BookingEligibility.check(with(base, "classActive", false)), ErrorCode.CLASS_NOT_BOOKABLE);
        assertCode(() -> BookingEligibility.check(with(base, "now", local("2026-10-15T18:50", MADRID))), ErrorCode.CLASS_NOT_BOOKABLE);
        var later = assertCode(() -> BookingEligibility.check(with(base, "week", RelativeWeek.LATER)), ErrorCode.NOT_YET_OPEN);
        assertThat(later.details()).containsEntry("opensAt", local("2026-10-11T20:00", MADRID).toString());
        assertThat(BookingEligibility.covers(new BookingEligibility.Period(LocalDate.parse("2026-11-01"), null), LocalDate.parse("2027-01-01"))).isTrue();
    }

    @Test void T_08_42_daylightSavingCancellationNoticeAndInstructorVisibilityDifferPerClubZone() {
        var start = local("2026-10-25T09:00", MADRID);
        assertThat(start).isEqualTo(Instant.parse("2026-10-25T08:00:00Z"));
        var at0630 = CancellationPolicy.evaluate(start, local("2026-10-25T06:30", MADRID), 120);
        assertThat(at0630).isEqualTo(new CancellationPolicy.Outcome(false, 150));
        assertThat(CancellationPolicy.notifyWaitlist(true, start, local("2026-10-25T06:30", MADRID), 30, true)).isTrue();
        assertThat(CancellationPolicy.evaluate(start, local("2026-10-25T07:00:01", MADRID), 120).late()).isTrue();
        // With the catalog's 240 the same instants are late; the boundary moves to 05:00 local.
        assertThat(CancellationPolicy.evaluate(start, local("2026-10-25T05:00", MADRID), 240).late()).isFalse();
        assertThat(CancellationPolicy.evaluate(start, local("2026-10-25T06:30", MADRID), 240).late()).isTrue();
        var visibleAt = InstructorVisibility.visibleAt(start, 24);
        assertThat(visibleAt.atZone(MADRID).toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-10-24T10:00"));
        assertThat(Duration.between(visibleAt.atZone(MADRID).toLocalDateTime(), start.atZone(MADRID).toLocalDateTime())).isEqualTo(Duration.ofHours(23));
        // The same UTC instant in Buenos Aires: another local hour and another booking week.
        var sundayNight = Instant.parse("2026-10-25T21:00:00Z");
        assertThat(new BookingWeeks(SUNDAY_20, MADRID).week(sundayNight).key()).isEqualTo("2026-10-25");
        assertThat(new BookingWeeks(SUNDAY_20, BUENOS_AIRES).week(sundayNight).key()).isEqualTo("2026-10-18");
        assertThat(start.atZone(BUENOS_AIRES).toLocalTime()).isEqualTo(LocalTime.parse("05:00"));
        assertThat(BookingEligibility.leaving(LocalDate.parse("2026-10-24"), start, BUENOS_AIRES)).isTrue();
        assertThat(BookingEligibility.leaving(LocalDate.parse("2026-10-25"), start, BUENOS_AIRES)).isFalse();
    }

    @Test void displayStateAndCalendarBodies() {
        var ends = Instant.parse("2026-10-15T17:50:00Z");
        assertThat(BookingDisplay.of(BookingState.ACTIVE, ends, ends.minusSeconds(1), false)).isEqualTo(BookingDisplay.State.CONFIRMED);
        assertThat(BookingDisplay.of(BookingState.ACTIVE, ends, ends, false)).isEqualTo(BookingDisplay.State.DONE);
        assertThat(BookingDisplay.of(BookingState.ACTIVE, ends, ends, true)).isEqualTo(BookingDisplay.State.NO_SHOW);
        for (var s : List.of(BookingState.CANCELLED, BookingState.CANCELLED_LATE, BookingState.CANCELLED_BY_CLUB, BookingState.PAYMENT_PENDING)) {
            assertThat(BookingDisplay.of(s, ends, ends, false).name()).isEqualTo(s.name());
        }
        var event = new BookingCalendar.Event("b1@club", "B+C · Duna", "Central; pista, 1", Instant.parse("2026-10-15T16:50:00Z"), ends, Instant.parse("2026-10-08T14:00:12Z"));
        assertThat(BookingCalendar.ics(event)).contains("DTSTART:20261015T165000Z", "DTEND:20261015T175000Z", "SUMMARY:B+C · Duna", "LOCATION:Central\\; pista\\, 1", "UID:b1@club")
                .startsWith("BEGIN:VCALENDAR\r\n").endsWith("END:VCALENDAR\r\n");
        assertThat(BookingCalendar.google(event)).startsWith("https://calendar.google.com/").contains("dates=20261015T165000Z/20261015T175000Z");
        assertThat(BookingCalendar.outlook(event)).startsWith("https://outlook.live.com/").contains("startdt=2026-10-15T16%3A50%3A00Z");
        assertThat(BookingSms.compact("Àlex ha reservat «B+C» — prova")).isEqualTo("Alex ha reservat  B+C    prova");
        assertThat(BookingSms.compact("x".repeat(200))).hasSize(160).endsWith("...");
    }

    private static BookingEligibility.Person person(String status, boolean blocked, String reason) { return new BookingEligibility.Person(status, blocked, reason, null); }
    private static BookingEligibility.Input input(boolean levels, List<String> classLevels, String dogLevel) {
        return new BookingEligibility.Input(true, true, person("ACTIVE", false, null), person("ACTIVE", false, null), Optional.empty(), levels, dogLevel,
                classLevels, true, local("2026-10-15T18:50", MADRID), MADRID, local("2026-10-08T10:00", MADRID), RelativeWeek.NEXT,
                local("2026-10-11T20:00", MADRID), Optional.empty());
    }
    @SuppressWarnings("unchecked")
    private static BookingEligibility.Input with(BookingEligibility.Input in, String field, Object value) {
        return new BookingEligibility.Input(field.equals("dogAccessible") ? (Boolean) value : in.dogAccessible(), field.equals("dogActive") ? (Boolean) value : in.dogActive(),
                field.equals("owner") ? (BookingEligibility.Person) value : in.owner(), field.equals("booker") ? (BookingEligibility.Person) value : in.booker(),
                field.equals("inactivity") ? (Optional<BookingEligibility.Period>) value : in.inactivity(), in.levelsEnabled(), in.dogLevelId(), in.classLevelIds(),
                field.equals("classActive") ? (Boolean) value : in.classActive(), in.classStartsAt(), in.zone(), field.equals("now") ? (Instant) value : in.now(),
                field.equals("week") ? (RelativeWeek) value : in.week(), in.opensAt(), field.equals("pack") ? (Optional<BookingEligibility.Pack>) value : in.pack());
    }
    private static ApiException assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        var thrown = catchThrowableOfType(ApiException.class, call);
        assertThat(thrown).as("expected " + code).isNotNull(); assertThat(thrown.code()).isEqualTo(code);
        return thrown;
    }
}
