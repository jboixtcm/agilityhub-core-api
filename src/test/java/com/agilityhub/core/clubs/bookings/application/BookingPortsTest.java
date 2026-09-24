package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.*;
import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.MockClock;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

/** The local/test stand-ins of the E6/E8 ports, the `.ics` capability and the actor/event envelopes, without Spring. */
class BookingPortsTest {
    @Test void attendanceDefaultsCountOnlyPresentAndNoShowMarks() {
        AttendanceStatePort present = id -> Optional.of("PRESENT"), noShow = id -> Optional.of("NO_SHOW"), pending = id -> Optional.of("PENDING"), none = id -> Optional.empty();
        assertThat(List.of(present.marked("b"), noShow.marked("b"), pending.marked("b"), none.marked("b"))).containsExactly(true, true, false, false);
        assertThat(List.of(present.noShow("b"), noShow.noShow("b"), none.noShow("b"))).containsExactly(false, true, false);
    }

    @Test void thePackStandInConsumesRefundsAndNeverRevivesAnExpiredPack() {
        var packs = new InMemoryPackBalances();
        try (var tenant = TenantContext.open("club-fixture")) {
            assertThat(packs.balance("m", "d")).isEmpty(); assertThat(packs.consume("m", "d", "b")).isNull();
            packs.open("m", "d", 2, 1, LocalDate.parse("2026-11-12"));
            assertThat(packs.consume("m", "d", "b1")).isNotNull(); assertThat(packs.balance("m", "d").orElseThrow().available()).isZero();
            assertThat(packs.consume("m", "d", "b2")).as("never below zero").isNotNull(); assertThat(packs.balance("m", "d").orElseThrow().consumed()).isEqualTo(2);
            assertThat(packs.refund("m", "d", "b1", LocalDate.parse("2026-11-12"))).isNotNull(); assertThat(packs.balance("m", "d").orElseThrow().available()).isEqualTo(1);
            assertThat(packs.refund("m", "d", "b1", LocalDate.parse("2026-11-13"))).isNull();
            packs.open("m", "open", 5, 0, null); assertThat(packs.refund("m", "open", "x", LocalDate.parse("2030-01-01"))).isNotNull();
            packs.clear(); assertThat(packs.balance("m", "d")).isEmpty();
        }
        try (var tenant = TenantContext.open("other-club")) { assertThat(packs.balance("m", "d")).isEmpty(); }
    }

    @Test void theInactivityStandInCoversInclusiveAndOpenEndedPeriodsPerClub() {
        var periods = new InMemoryInactivity();
        try (var tenant = TenantContext.open("club-fixture")) {
            periods.approve("m", LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-31")); periods.approve("n", LocalDate.parse("2026-11-01"), null);
            assertThat(periods.covering("m", LocalDate.parse("2026-11-01"))).isPresent(); assertThat(periods.covering("m", LocalDate.parse("2026-12-31"))).isPresent();
            assertThat(periods.covering("m", LocalDate.parse("2026-10-31"))).isEmpty(); assertThat(periods.covering("m", LocalDate.parse("2027-01-01"))).isEmpty();
            assertThat(periods.covering("n", LocalDate.parse("2030-01-01"))).isPresent();
        }
        try (var tenant = TenantContext.open("other-club")) { assertThat(periods.covering("m", LocalDate.parse("2026-11-15"))).isEmpty(); }
        periods.clear();
    }

    @Test void calendarTokensAreTenantBoundExpireAtClassEndAndNeedAKeyInStagingOrProd() {
        var clock = new MockClock(Instant.parse("2026-10-07T10:00:00Z"));
        var key = Base64.getEncoder().encodeToString(new byte[32]);
        var tokens = new BookingCalendarTokens(new MockEnvironment().withProperty("bookings.calendar-key", key), clock);
        String token;
        try (var tenant = TenantContext.open("club-a")) {
            token = tokens.issue("b1", Instant.parse("2026-10-07T17:50:00Z"));
            assertThatCode(() -> tokens.require("b1", token)).doesNotThrowAnyException();
            for (String bad : List.of("x", "1.x", token + "x", "abc.def")) {
                assertThat(catchThrowableOfType(ApiException.class, () -> tokens.require("b1", bad)).code()).isEqualTo(ErrorCode.NOT_FOUND);
            }
            assertThat(catchThrowableOfType(ApiException.class, () -> tokens.require("b2", token)).code()).isEqualTo(ErrorCode.NOT_FOUND);
            clock.setInstant(Instant.parse("2026-10-07T17:50:00Z"));
            assertThat(catchThrowableOfType(ApiException.class, () -> tokens.require("b1", token)).code()).isEqualTo(ErrorCode.NOT_FOUND);
        }
        clock.setInstant(Instant.parse("2026-10-07T10:00:00Z"));
        try (var tenant = TenantContext.open("club-b")) {
            assertThat(catchThrowableOfType(ApiException.class, () -> tokens.require("b1", token)).code()).isEqualTo(ErrorCode.NOT_FOUND);
        }
        var local = new BookingCalendarTokens(new MockEnvironment(), clock);
        try (var tenant = TenantContext.open("club-a")) { assertThat(local.issue("b1", Instant.MAX.minusSeconds(1)).split("\\.")).hasSize(2); }
        var staging = new MockEnvironment(); staging.setActiveProfiles("staging");
        assertThatThrownBy(() -> new BookingCalendarTokens(staging, clock)).hasMessageContaining("Missing bookings.calendar-key");
        assertThatThrownBy(() -> new BookingCalendarTokens(new MockEnvironment().withProperty("bookings.calendar-key", "%%%"), clock)).hasMessageContaining("Invalid");
        assertThatThrownBy(() -> new BookingCalendarTokens(new MockEnvironment().withProperty("bookings.calendar-key", "c2hvcnQ="), clock)).hasMessageContaining("32 bytes");
    }

    @Test void notificationCodesFollowOriginActorAndReason() {
        java.util.function.BiFunction<BookingEvent.Kind, Map<String, Object>, BookingEvent> event = (kind, payload) ->
                new BookingEvent(kind, "c", "b", Instant.EPOCH, payload, null, null, DomainEvent.Origin.APP);
        var createdApp = event.apply(BookingEvent.Kind.BookingCreated, Map.of("origin", "APP"));
        var createdInstructor = event.apply(BookingEvent.Kind.BookingCreated, Map.of("origin", "INSTRUCTOR"));
        var createdClub = event.apply(BookingEvent.Kind.BookingCreated, Map.of("origin", "BACKOFFICE"));
        var byMember = event.apply(BookingEvent.Kind.BookingCancelled, Map.of("origin", "APP", "by", "MEMBER", "reason", "MEMBER"));
        var byInstructor = event.apply(BookingEvent.Kind.BookingCancelled, Map.of("origin", "INSTRUCTOR", "by", "INSTRUCTOR", "reason", "INSTRUCTOR_NOTICE"));
        var byClub = event.apply(BookingEvent.Kind.BookingCancelled, Map.of("origin", "BACKOFFICE", "by", "ADMIN", "reason", "MEMBER"));
        var bySystem = event.apply(BookingEvent.Kind.BookingCancelled, Map.of("origin", "SYSTEM", "by", "SYSTEM", "reason", "INACTIVITY"));
        var timeout = event.apply(BookingEvent.Kind.BookingCancelled, Map.of("origin", "SYSTEM", "by", "SYSTEM", "reason", "PAYMENT_TIMEOUT"));
        var memberTimeout = event.apply(BookingEvent.Kind.BookingCancelled, Map.of("origin", "APP", "by", "MEMBER", "reason", "PAYMENT_TIMEOUT"));
        var clubAsMember = event.apply(BookingEvent.Kind.BookingCancelled, Map.of("origin", "BACKOFFICE", "by", "MEMBER", "reason", "MEMBER"));
        var cases = new LinkedHashMap<String, List<BookingEvent>>();
        cases.put("N-04", List.of(createdApp, createdInstructor)); cases.put("N-05", List.of(byMember, byInstructor));
        cases.put("N-36", List.of(createdClub, byClub, clubAsMember)); cases.put("N-40", List.of(timeout, memberTimeout));
        var all = List.of(createdApp, createdInstructor, createdClub, byMember, byInstructor, byClub, bySystem, timeout, memberTimeout, clubAsMember);
        cases.forEach((code, expected) -> assertThat(all.stream().filter(e -> BookingNotifications.code(code, e).isPresent()).toList()).as(code).isEqualTo(expected));
        assertThat(BookingNotifications.code("N-15", createdApp)).isEmpty();
        assertThat(BookingNotifications.code("N-05", event.apply(BookingEvent.Kind.BookingCancelled, Map.of()))).isEmpty();
    }

    @Test void transactionsRetryWriteConflictsAtMostThreeTimesAndNeverOtherFailures() {
        var template = org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class);
        var conflict = new com.mongodb.MongoCommandException(new org.bson.BsonDocument("code", new org.bson.BsonInt32(112)).append("errmsg", new org.bson.BsonString("WriteConflict")),
                new com.mongodb.ServerAddress());
        var transient_ = new com.mongodb.MongoException("transient"); transient_.addLabel("TransientTransactionError");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(template.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            if (call == 1) { throw new org.springframework.dao.DataIntegrityViolationException("wrapped", conflict); }
            if (call == 2) { throw transient_; }
            return "done";
        });
        var transactions = new BookingTransactions(template);
        try (var tenant = TenantContext.open("club-fixture")) {
            assertThat(transactions.write(Arrays.asList("class-b", null, "class-a"), () -> "work")).isEqualTo("done");
            assertThat(calls.get()).isEqualTo(3);
            calls.set(0);
            org.mockito.Mockito.doAnswer(invocation -> { calls.incrementAndGet(); throw transient_; }).when(template).execute(org.mockito.ArgumentMatchers.any());
            assertThat(catchThrowableOfType(ApiException.class, () -> transactions.write(List.of("class-a"), () -> "work")).code()).isEqualTo(ErrorCode.STALE_VERSION);
            assertThat(calls.get()).isEqualTo(BookingTransactions.ATTEMPTS);
            org.mockito.Mockito.doThrow(new ApiException(ErrorCode.CLASS_FULL)).when(template).execute(org.mockito.ArgumentMatchers.any());
            assertThat(catchThrowableOfType(ApiException.class, () -> transactions.write(List.of("class-a"), () -> "work")).code()).isEqualTo(ErrorCode.CLASS_FULL);
        }
        assertThat(BookingTransactions.transientConflict(new IllegalStateException(new RuntimeException()))).isFalse();
    }

    @Test void demoRowsRejectImpossibleCounts() {
        assertThatCode(() -> new DemoBookingSeeder.Row(2, DayOfWeek.MONDAY, "08:30", "CEN", 4, 2, 1)).doesNotThrowAnyException();
        for (int[] bad : new int[][]{{-1, 0, 0}, {1, 0, -1}, {1, -1, 0}, {1, 2, 0}}) {
            assertThatThrownBy(() -> new DemoBookingSeeder.Row(2, DayOfWeek.MONDAY, "08:30", "CEN", bad[0], bad[1], bad[2])).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void aPayToBookCheckoutIsNeverOpenedInsideATransaction() {
        var confirmations = new BookingConfirmationService(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var plain = new BookingConfirmationService.Confirmed(null, null, null);
        assertThat(confirmations.openCheckout(plain)).as("nothing to open").isSameAs(plain);
        var pending = new BookingConfirmationService.Confirmed(null, null,
                new SingleClassChargePort.Pending("session", "payment", "m", "b", new Money(1200, "EUR"), "Classe", Instant.EPOCH));
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> confirmations.openCheckout(pending)).isInstanceOf(IllegalStateException.class); }
        finally { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    @Test void actorsAndForeignEnvelopes() {
        assertThat(BookingActor.system().isSystem()).isTrue(); assertThat(BookingActor.system().impersonated()).isFalse();
        var member = BookingActor.member("acc", "m", "Laura");
        assertThat(member.origin()).isEqualTo(BookingOrigin.APP); assertThat(member.role()).isEqualTo(ActorRole.MEMBER);
        try (var user = CurrentUser.open(new CurrentUser("member-acc", "Laura", new CurrentUser.Impersonation("admin-acc", "Admin", "m"), DomainEvent.Origin.BACKOFFICE))) {
            var imp = BookingActor.member("member-acc", "ignored", "Laura");
            assertThat(imp).isEqualTo(new BookingActor("admin-acc", "m", "Admin", "m", BookingOrigin.BACKOFFICE, ActorRole.ADMIN)); assertThat(imp.impersonated()).isTrue();
        }
        assertThat(BookingActor.instructor("inst", "Estela").origin()).isEqualTo(BookingOrigin.INSTRUCTOR);
        var kind = new ForeignEvent("ClassSessionUpdated", null, "c", null, "id", Instant.EPOCH, null, null, null, DomainEvent.Origin.SYSTEM);
        assertThat(kind.type()).isEqualTo("ClassSessionUpdated"); assertThat(kind.payload()).isEmpty();
        assertThat(new ForeignEvent(null, "UpfrontPaymentFailed", "c", "UpfrontPayment", "id", Instant.EPOCH, Map.of("bookingId", "b"), null, null, null).type()).isEqualTo("UpfrontPaymentFailed");
        var weeks = new BookingWeeks(BookingWeeks.Opening.of(Map.of("dayOfWeek", "SATURDAY", "time", "02:30")), ZoneId.of("Europe/Madrid"));
        assertThat(weeks.zone()).isEqualTo(ZoneId.of("Europe/Madrid"));
        // An opening inside a DST gap shifts forward (29-03-2026 is a Sunday; the Saturday before is unaffected).
        assertThat(weeks.week(Instant.parse("2026-03-30T10:00:00Z")).key()).isEqualTo("2026-03-28");
    }
}
