package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.AttendanceConsumers;
import com.agilityhub.core.clubs.bookings.application.NoShowNoticeClaims;
import com.agilityhub.core.clubs.bookings.domain.AttendanceEvent;
import com.agilityhub.core.clubs.bookings.domain.BookingEvent;
import com.agilityhub.core.clubs.bookings.domain.ForeignEvent;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * S10 WP-10-B attendance (E6-T02): the sheet of 21/D12 (R-10-02/03), the bulk save in one transaction with S08's «ha
 * avisat» cancellation (R-10-04/05), idempotency, concurrency (T-10-23/24), the N-19 claim (R-10-06), the S08
 * contract (R-08-10) and the consumers (§7), over the fictional S08 club (Europe/Madrid).
 */
@Import(AttendanceIT.S12Double.class)
class AttendanceIT extends BookingFixtures {
    /** T-10-13: a stand-in for S12's `AttendanceMarked` consumer (R-10-07), which records every delivery. */
    @TestConfiguration(proxyBeanMethods = false)
    static class S12Double {
        static final List<Map<String, Object>> RECEIVED = new CopyOnWriteArrayList<>();
        @Bean("test.S12.AttendanceMarked") DomainEventHandler<AttendanceEvent> s12() {
            return new DomainEventHandler<>() {
                public String eventType() { return "AttendanceMarked"; } public Class<AttendanceEvent> eventClass() { return AttendanceEvent.class; }
                public void handle(String id, AttendanceEvent event) { var copy = new LinkedHashMap<String, Object>(event.payload()); copy.put("eventId", id); RECEIVED.add(copy); }
            };
        }
    }
    @Autowired NoShowNoticeClaims claims; @Autowired AttendanceConsumers consumers; @Autowired com.agilityhub.core.clubs.bookings.application.ClaimNoShowCommand command;

    JsonNode sheet(String classId, RequestPostProcessor auth) throws Exception { return call(HttpMethod.GET, "/class-sessions/s08-" + classId + "/attendance", null, auth, 200); }
    static Map<String, Object> body(long version, Object... items) {
        var list = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < items.length; i += 2) { list.add(Map.of("bookingId", items[i], "state", items[i + 1])); }
        return Map.of("version", version, "items", list);
    }
    JsonNode save(String classId, RequestPostProcessor auth, int expected, long version, Object... items) throws Exception {
        return call(HttpMethod.PUT, "/class-sessions/s08-" + classId + "/attendance", body(version, items), auth, expected, UUID.randomUUID().toString());
    }
    JsonNode row(JsonNode sheet, String bookingId) {
        for (var row : sheet.path("rows")) { if (row.path("bookingId").asText().equals(bookingId)) { return row; } }
        return mapper.missingNode();
    }
    Document attendance(String bookingId) { return mongo.findOne(Query.query(Criteria.where("bookingId").is(bookingId)), Document.class, "attendances"); }
    Document summary(String classId) { return session(classId).get("attendanceSummary", Document.class); }
    long notifications(String code, String channel) {
        return count("notifications", channel == null ? Criteria.where("code").is(code) : Criteria.where("code").is(code).and("channel").is(channel));
    }
    String id(JsonNode booking) { return booking.path("id").asText(); }

    @Test void T_10_10_theSheetIsTheLiveBookingsPlusTheNotifiedOnesByBookedAt() throws Exception {
        var duna = id(book(as("laura"), "wed", "s08-d-duna"));
        clock.setInstant(NOW.plusSeconds(60)); var toby = id(book(as("joan"), "wed", "s08-d-toby"));
        clock.setInstant(NOW.plusSeconds(120)); var nit = id(book(as("pere"), "wed", "s08-d-nit"));
        join(as("c0"), "wed", "s08-d-c0", 201);
        cancel(as("pere"), nit, 200); // the member's own cancellation released the seat: out of the sheet
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-d-duna")), new Update().set("handlerName", "Marta"), "dogs");
        clock.setInstant(local("2026-10-07T18:00"));
        var before = sheet("wed", as("inst"));
        assertThat(before.path("rows").findValuesAsText("bookingId")).containsExactly(duna, toby);
        assertThat(before.path("classSession").path("booked").asInt()).isEqualTo(2);
        assertThat(before.path("classSession").path("waiting").asInt()).isEqualTo(1);
        assertThat(before.path("sheet").path("version").asLong()).isZero();
        assertThat(before.path("sheet").path("canMarkPresence").asBoolean()).isTrue();
        assertThat(before.path("sheet").path("canMarkNotice").asBoolean()).isTrue();
        assertThat(before.path("sheet").path("editableUntil").asText()).isEqualTo("2026-10-08T21:59:59Z");
        assertThat(before.path("sheet").path("noShowNoticeTime").asText()).isEqualTo("08:00");
        var first = row(before, duna);
        assertThat(first.path("state").asText()).isEqualTo("PENDING"); assertThat(first.path("final").asBoolean()).isFalse();
        assertThat(first.path("dogName").asText()).isEqualTo("Duna"); assertThat(first.path("memberFirstName").asText()).isEqualTo("Laura");
        // R-10-00: someone else handles Duna, so the backoffice row names the owner too.
        assertThat(first.path("handlerName").asText()).isEqualTo("Marta"); assertThat(first.path("memberFullName").asText()).isEqualTo("Laura Example");
        assertThat(row(before, toby).has("memberFullName")).isFalse();
        assertThat(first.path("levelCode").asText()).isEqualTo("C");
        // E6-T03 has not implemented the follow-up port yet: no pendingTasksCount, as with TASKS off.
        assertThat(first.has("pendingTasksCount")).isFalse();
        assertThat(before.path("waitlist").path("mode").asText()).isEqualTo("ALL_AT_ONCE");
        assertThat(before.path("waitlist").path("entries").findValuesAsText("dogName")).containsExactly("Dog0");

        var saved = save("wed", as("inst"), 200, 0, toby, "NOTIFIED");
        assertThat(saved.path("applied").size()).isEqualTo(1);
        assertThat(saved.path("applied").get(0).asText()).isEqualTo(toby);
        var after = sheet("wed", as("admin"));
        assertThat(after.path("rows").findValuesAsText("bookingId")).containsExactly(duna, toby);
        var notified = row(after, toby);
        assertThat(notified.path("state").asText()).isEqualTo("NOTIFIED"); assertThat(notified.path("final").asBoolean()).isTrue();
        assertThat(notified.path("notice").path("bookingState").asText()).isEqualTo("CANCELLED_LATE");
        assertThat(notified.path("notice").path("atLocal").asText()).isEqualTo("18:00");
        assertThat(notified.path("markedByName").asText()).isEqualTo("Estela");
        assertThat(after.path("sheet").path("version").asLong()).isEqualTo(1);
        assertThat(after.path("sheet").path("savedByName").asText()).isEqualTo("Estela");
        assertThat(after.path("classSession").path("booked").asInt()).isEqualTo(1);
        assertThat(after.has("applied")).isFalse();

        // Modules off: no waitlist block, no waiting (S10 §9).
        modules(Module.FAMILY_GROUP);
        var minimal = sheet("wed", as("inst"));
        assertThat(minimal.has("waitlist")).isFalse(); assertThat(minimal.path("classSession").has("waiting")).isFalse();
        assertThat(row(minimal, duna).has("pendingTasksCount")).isFalse();
        // A class the club cancelled: inert rows (both flags false). A DRAFT class is not on any instructor screen.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-wed")), new Update().set("state", "CANCELLED"), "class_sessions");
        var cancelled = sheet("wed", as("inst"));
        assertThat(cancelled.path("sheet").path("canMarkPresence").asBoolean()).isFalse();
        assertThat(cancelled.path("sheet").path("canMarkNotice").asBoolean()).isFalse();
        assertThat(code(save("wed", as("admin"), 409, 1, duna, "PRESENT"))).isEqualTo("INVALID_STATE");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-wed")), new Update().set("state", "DRAFT"), "class_sessions");
        assertThat(code(call(HttpMethod.GET, "/class-sessions/s08-wed/attendance", null, as("inst"), 404))).isEqualTo("NOT_FOUND");
    }

    @Test void T_10_11_haAvisatInTimeCancelsReleasesTheSeatAndTellsTheWaitingListOnceThenLateThenAfterTheEnd() throws Exception {
        openPack("s08-m-laura", "s08-d-duna", 10, 0, LocalDate.of(2026, 12, 31));
        var duna = id(book(as("laura"), "thu", "s08-d-duna"));
        var toby = id(book(as("joan"), "thu", "s08-d-toby"));
        var nit = id(book(as("pere"), "thu", "s08-d-nit"));
        join(as("c0"), "thu", "s08-d-c0", 201); join(as("c1"), "thu", "s08-d-c1", 201);
        assertThat(booking(duna).getString("packMovementId")).isNotNull();

        // Thu 15:00 → 14:50:00 is exactly 240 minutes before 18:50: still in time (catalog value; the spec table uses 120).
        clock.setInstant(local("2026-10-08T14:50"));
        var saved = save("thu", as("inst"), 200, 0, duna, "NOTIFIED");
        var notice = row(saved, duna).path("notice");
        assertThat(notice.path("late").asBoolean()).isFalse(); assertThat(notice.path("minutesBefore").asInt()).isEqualTo(240);
        assertThat(notice.path("seatReleased").asBoolean()).isTrue(); assertThat(notice.path("waitlistNotified").asBoolean()).isTrue();
        assertThat(notice.path("afterClassEnd").asBoolean()).isFalse(); assertThat(notice.path("bookingState").asText()).isEqualTo("CANCELLED");
        var cancelled = booking(duna);
        assertThat(cancelled.getString("state")).isEqualTo("CANCELLED"); assertThat(cancelled.getString("cancelReason")).isEqualTo("INSTRUCTOR_NOTICE");
        assertThat(cancelled.get("cancelledBy", Document.class).getString("role")).isEqualTo("INSTRUCTOR");
        assertThat(cancelled.getString("packRefundMovementId")).as("R-08-17: the pack session is given back in time").isNotNull();
        assertThat(eventsOf("BookingCancelled")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class))
                .containsEntry("by", "INSTRUCTOR").containsEntry("origin", "INSTRUCTOR").containsEntry("late", false).containsEntry("reason", "INSTRUCTOR_NOTICE"));
        assertThat(eventsOf("SeatReleased")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class)).containsEntry("notifyWaitlist", true));
        assertThat(eventsOf("AttendanceMarked")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class))
                .containsEntry("state", "NOTIFIED").containsEntry("previousState", "PENDING").containsEntry("late", false).containsEntry("afterClassEnd", false)
                .containsEntry("dogId", "s08-d-duna").containsEntry("memberId", "s08-m-laura").containsEntry("classSessionId", "s08-thu"));
        assertThat(session("thu").get("counters", Document.class)).containsEntry("booked", 2);
        dispatch(); dispatch();
        assertThat(events("WaitlistNotified")).isEqualTo(1);
        assertThat(notifications("N-15", "APP")).as("one N-15 per waiting entry, once").isEqualTo(2);
        assertThat(notifications("N-05", "APP")).isEqualTo(1);
        assertThat(notifications("N-05", "SMS")).as("R-08-19: the member's own initiative, no SMS").isZero();
        assertThat(notifications("N-36", null)).as("not the backoffice variant").isZero();
        long n15 = notifications("N-15", null);

        // 18:25: late (CANCELLED_LATE, no refund) and 25 minutes left: the seat is released without telling anyone.
        clock.setInstant(local("2026-10-08T18:25"));
        var late = row(save("thu", as("inst"), 200, 1, toby, "NOTIFIED"), toby).path("notice");
        assertThat(late.path("late").asBoolean()).isTrue(); assertThat(late.path("bookingState").asText()).isEqualTo("CANCELLED_LATE");
        assertThat(late.path("waitlistNotified").asBoolean()).isFalse(); assertThat(late.path("minutesBefore").asInt()).isEqualTo(25);
        assertThat(booking(toby).getString("packRefundMovementId")).isNull();
        assertThat(eventsOf("SeatReleased")).hasSize(2).last().satisfies(e -> assertThat(e.get("payload", Document.class)).containsEntry("notifyWaitlist", false));
        dispatch();
        assertThat(notifications("N-15", null)).isEqualTo(n15);
        assertThat(count("audit_entries", Criteria.where("action").is("BOOKING_CANCELLED_LATE"))).isEqualTo(1);

        // 20:30, the class has ended: only a record, the booking stays ACTIVE (it counts) and nobody is told.
        clock.setInstant(local("2026-10-08T20:30"));
        var after = row(save("thu", as("inst"), 200, 2, nit, "NOTIFIED"), nit);
        assertThat(after.path("notice").path("afterClassEnd").asBoolean()).isTrue();
        assertThat(after.path("notice").path("bookingState").asText()).isEqualTo("ACTIVE");
        assertThat(after.path("notice").path("seatReleased").asBoolean()).isFalse();
        assertThat(after.path("notice").path("minutesBefore").isNull()).isTrue();
        assertThat(booking(nit).getString("state")).isEqualTo("ACTIVE");
        assertThat(events("BookingCancelled")).isEqualTo(2); assertThat(events("SeatReleased")).isEqualTo(2);
        dispatch();
        assertThat(notifications("N-05", null)).isEqualTo(2);
        assertThat(summary("thu")).containsEntry("version", 3).containsEntry("marked", 3).containsEntry("notified", 3).containsEntry("notifiedAfterEnd", 1);

        // bookings.instructorLastMinuteNotice = false: 422 and nothing changes.
        parameter("bookings.instructorLastMinuteNotice", false);
        var other = id(book(as("laura"), "mon", "s08-d-duna"));
        clock.setInstant(local("2026-10-12T12:00"));
        assertThat(code(save("mon", as("inst"), 422, 0, other, "NOTIFIED"))).isEqualTo("INSTRUCTOR_NOTICE_DISABLED");
        assertThat(booking(other).getString("state")).isEqualTo("ACTIVE"); assertThat(attendance(other)).isNull();
        assertThat(summary("mon")).containsEntry("version", 0).containsEntry("marked", 0);
    }

    @Test void T_10_12_anInvalidItemRollsBackTheWholeSaveAStaleVersionGetsTheCurrentSheetAndTheSameKeyReplays() throws Exception {
        var duna = id(book(as("laura"), "fri", "s08-d-duna"));
        clock.setInstant(NOW.plusSeconds(60)); // rows are ordered by bookedAt
        var toby = id(book(as("joan"), "fri", "s08-d-toby"));
        var nit = id(book(as("pere"), "fri", "s08-d-nit"));
        cancel(as("pere"), nit, 200);
        clock.setInstant(local("2026-10-09T20:30"));
        var refused = save("fri", as("inst"), 422, 0, duna, "PRESENT", toby, "NO_SHOW", nit, "PRESENT");
        assertThat(code(refused)).isEqualTo("ATTENDANCE_BOOKING_NOT_ACTIVE");
        assertThat(refused.path("details").path("bookingId").asText()).isEqualTo(nit);
        assertThat(count("attendances", Criteria.where("classSessionId").is("s08-fri"))).as("rollback: none of the three").isZero();
        assertThat(events("AttendanceMarked")).isZero(); assertThat(summary("fri")).containsEntry("version", 0).containsEntry("marked", 0);
        assertThat(code(save("fri", as("inst"), 400, 0, duna, "PRESENT", duna, "NO_SHOW"))).isEqualTo("VALIDATION_ERROR");

        var stale = save("fri", as("inst"), 409, 4, duna, "PRESENT");
        assertThat(code(stale)).isEqualTo("STALE_VERSION");
        assertThat(stale.path("details").path("current").path("sheet").path("version").asLong()).isZero();
        assertThat(stale.path("details").path("current").path("rows").findValuesAsText("bookingId")).containsExactly(duna, toby);

        String key = UUID.randomUUID().toString();
        var first = call(HttpMethod.PUT, "/class-sessions/s08-fri/attendance", body(0, duna, "PRESENT", toby, "NO_SHOW"), as("inst"), 200, key);
        var again = call(HttpMethod.PUT, "/class-sessions/s08-fri/attendance", body(0, duna, "PRESENT", toby, "NO_SHOW"), as("inst"), 200, key);
        assertThat(again).isEqualTo(first);
        assertThat(first.path("applied").size()).isEqualTo(2);
        assertThat(events("AttendanceMarked")).as("one per item, the replay emits nothing").isEqualTo(2);
        assertThat(code(call(HttpMethod.PUT, "/class-sessions/s08-fri/attendance", body(0, duna, "NO_SHOW"), as("inst"), 409, key))).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(summary("fri")).containsEntry("version", 1).containsEntry("marked", 2).containsEntry("present", 1).containsEntry("noShow", 1)
                .containsEntry("notified", 0).containsEntry("savedByName", "Estela");
        // Resending the whole sheet as it is: no-op, no event, same version.
        var resent = save("fri", as("inst"), 200, 1, duna, "PRESENT", toby, "NO_SHOW");
        assertThat(resent.path("applied").size()).isZero(); assertThat(resent.path("sheet").path("version").asLong()).isEqualTo(1);
        assertThat(events("AttendanceMarked")).isEqualTo(2);
        save("fri", as("inst"), 200, 1, toby, "PENDING");
        assertThat(summary("fri")).containsEntry("version", 2).containsEntry("marked", 1).containsEntry("noShow", 0);
        assertThat(attendance(toby).getList("history", Document.class)).extracting(h -> h.getString("state")).containsExactly("NO_SHOW", "PENDING");
        // An unknown state is 400.
        var unknown = mvc.perform(put("/api/v1/class-sessions/s08-fri/attendance").header("Host", HOST).header("Idempotency-Key", UUID.randomUUID().toString())
                .with(as("inst")).contentType("application/json").content("{\"version\":2,\"items\":[{\"bookingId\":\"" + duna + "\",\"state\":\"LATE\"}]}")).andReturn().getResponse();
        assertThat(unknown.getStatus()).isEqualTo(400);
    }

    @Test void T_10_13_attendanceMarkedReachesTheS12ConsumerOnceWithItsPreviousStateWithTheModuleOnOrOff() throws Exception {
        for (boolean singleClass : List.of(true, false)) {
            fixtures(); S12Double.RECEIVED.clear();
            if (!singleClass) { modules(Arrays.stream(Module.values()).filter(m -> m != Module.SINGLE_CLASS).toArray(Module[]::new)); }
            mongo.save(new Document("_id", "s08-plan-single").append("clubId", CLUB).append("type", "SINGLE_CLASS")
                    .append("singleClass", new Document("chargeMode", "CHARGE_ON_ATTENDANCE")), "plans");
            mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-laura")), new Update().set("planId", "s08-plan-single"), "members");
            var duna = id(book(as("laura"), "wed", "s08-d-duna"));
            clock.setInstant(local("2026-10-07T19:00"));
            save("wed", as("inst"), 200, 0, duna, "PRESENT");
            dispatch(); dispatch();
            assertThat(S12Double.RECEIVED).singleElement().satisfies(p -> assertThat(p).containsEntry("state", "PRESENT").containsEntry("previousState", "PENDING")
                    .containsEntry("bookingId", duna).containsEntry("by", Map.of("accountId", "s08-inst", "role", "INSTRUCTOR")));
            save("wed", as("inst"), 200, 1, duna, "PENDING");
            dispatch(); dispatch();
            assertThat(S12Double.RECEIVED).hasSize(2).last().satisfies(p -> assertThat(p).containsEntry("state", "PENDING").containsEntry("previousState", "PRESENT"));
            assertThat(S12Double.RECEIVED.stream().map(p -> p.get("eventId")).distinct()).hasSize(2);
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "upfront_payments")).as("S10 charges nothing itself").isZero();
        }
    }

    @Test void T_10_23_twoInstructorsSavingTheSameVersionGiveOne200AndOne409ThatReappliesItsOwnRow() throws Exception {
        account("inst2", "INSTRUCTOR", "s08-m-inst2"); member("s08-m-inst2", "inst2", "Neus", "ca");
        var duna = id(book(as("laura"), "sat", "s08-d-duna"));
        var toby = id(book(as("joan"), "sat", "s08-d-toby"));
        var nit = id(book(as("pere"), "sat", "s08-d-nit"));
        clock.setInstant(local("2026-10-10T09:30"));
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var estela = pool.submit(() -> { start.await(); return mvc.perform(put("/api/v1/class-sessions/s08-sat/attendance").header("Host", HOST)
                    .header("Idempotency-Key", UUID.randomUUID().toString()).with(as("inst")).contentType("application/json")
                    .content(mapper.writeValueAsBytes(body(0, duna, "PRESENT")))).andReturn().getResponse(); });
            var neus = pool.submit(() -> { start.await(); return mvc.perform(put("/api/v1/class-sessions/s08-sat/attendance").header("Host", HOST)
                    .header("Idempotency-Key", UUID.randomUUID().toString()).with(as("inst2")).contentType("application/json")
                    .content(mapper.writeValueAsBytes(body(0, nit, "NO_SHOW")))).andReturn().getResponse(); });
            start.countDown();
            var replies = List.of(estela.get(60, TimeUnit.SECONDS), neus.get(60, TimeUnit.SECONDS));
            var statuses = replies.stream().map(r -> r.getStatus()).sorted().toList();
            System.out.println("T-10-23 two saves of version 0 in parallel: statuses " + statuses);
            assertThat(statuses).containsExactly(200, 409);
            var loser = replies.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow();
            var conflict = mapper.readTree(loser.getContentAsString());
            assertThat(code(conflict)).isEqualTo("STALE_VERSION");
            assertThat(conflict.path("details").path("current").path("sheet").path("version").asLong()).isEqualTo(1);
            boolean estelaLost = loser == replies.get(0);
            String winnerRow = estelaLost ? nit : duna;
            assertThat(row(conflict.path("details").path("current"), winnerRow).path("state").asText()).isEqualTo(estelaLost ? "NO_SHOW" : "PRESENT");
            // The loser reloads and reapplies only its own row.
            if (estelaLost) { save("sat", as("inst"), 200, 1, duna, "PRESENT"); } else { save("sat", as("inst2"), 200, 1, nit, "NO_SHOW"); }
        } finally { pool.shutdownNow(); }
        var finalSheet = sheet("sat", as("admin"));
        assertThat(row(finalSheet, duna).path("state").asText()).isEqualTo("PRESENT");
        assertThat(row(finalSheet, nit).path("state").asText()).isEqualTo("NO_SHOW");
        assertThat(row(finalSheet, toby).path("state").asText()).isEqualTo("PENDING");
        assertThat(finalSheet.path("sheet").path("version").asLong()).isEqualTo(2);
        assertThat(summary("sat")).containsEntry("marked", 2).containsEntry("present", 1).containsEntry("noShow", 1);
        // The same PUT repeated with the same key is byte-identical.
        String key = UUID.randomUUID().toString();
        var once = mvc.perform(put("/api/v1/class-sessions/s08-sat/attendance").header("Host", HOST).header("Idempotency-Key", key).with(as("inst"))
                .contentType("application/json").content(mapper.writeValueAsBytes(body(2, toby, "PRESENT")))).andReturn().getResponse();
        var twice = mvc.perform(put("/api/v1/class-sessions/s08-sat/attendance").header("Host", HOST).header("Idempotency-Key", key).with(as("inst"))
                .contentType("application/json").content(mapper.writeValueAsBytes(body(2, toby, "PRESENT")))).andReturn().getResponse();
        assertThat(once.getStatus()).isEqualTo(200); assertThat(twice.getContentAsByteArray()).isEqualTo(once.getContentAsByteArray());
    }

    @Test void T_10_24_haAvisatAndWaitingListClaimsOnTheSameClassNeverOverbook() throws Exception {
        for (int round = 0; round < 3; round++) {
            fixtures();
            session("race", "2026-10-12T18:50", 2, List.of());
            var duna = id(book(as("laura"), "race", "s08-d-duna"));
            var nit = id(book(as("pere"), "race", "s08-d-nit"));
            var toby = join(as("joan"), "race", "s08-d-toby", 201).path("id").asText();
            var dog0 = join(as("c0"), "race", "s08-d-c0", 201).path("id").asText();
            cancel(as("pere"), nit, 200);
            dispatch();
            assertThat(count("waitlist_entries", Criteria.where("classSessionId").is("s08-race").and("state").is("NOTIFIED"))).isEqualTo(2);
            clock.setInstant(local("2026-10-12T12:00"));
            var pool = Executors.newFixedThreadPool(3); var start = new CountDownLatch(1);
            var results = new ArrayList<Future<String>>();
            try {
                results.add(pool.submit(() -> { start.await();
                    return "save " + mvc.perform(put("/api/v1/class-sessions/s08-race/attendance").header("Host", HOST).header("Idempotency-Key", UUID.randomUUID().toString())
                            .with(as("inst")).contentType("application/json").content(mapper.writeValueAsBytes(body(0, duna, "NOTIFIED")))).andReturn().getResponse().getStatus(); }));
                for (var claim : List.of(new String[] {"joan", "s08-d-toby", toby}, new String[] {"c0", "s08-d-c0", dog0})) {
                    results.add(pool.submit(() -> { start.await();
                        var hold = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/seat-holds").header("Host", HOST).with(as(claim[0]))
                                .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("classSessionId", "s08-race", "dogId", claim[1], "waitlistEntryId", claim[2]))))
                                .andReturn().getResponse();
                        if (hold.getStatus() != 201) { return "hold " + hold.getStatus() + " " + mapper.readTree(hold.getContentAsString()).path("code").asText(); }
                        var claimed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/waitlist-entries/" + claim[2] + "/claim")
                                .header("Host", HOST).header("Idempotency-Key", UUID.randomUUID().toString()).with(as(claim[0])).contentType("application/json")
                                .content(mapper.writeValueAsBytes(Map.of("seatHoldId", mapper.readTree(hold.getContentAsString()).path("id").asText())))).andReturn().getResponse();
                        return "claim " + claimed.getStatus() + (claimed.getStatus() >= 400 ? " " + mapper.readTree(claimed.getContentAsString()).path("code").asText() : ""); }));
                }
                start.countDown();
                var outcomes = new ArrayList<String>(); for (var f : results) { outcomes.add(f.get(120, TimeUnit.SECONDS)); }
                long live = count("bookings", Criteria.where("classSessionId").is("s08-race").and("state").in("ACTIVE", "PAYMENT_PENDING"));
                System.out.println("T-10-24 round " + round + ": " + outcomes + " → live bookings " + live + " / capacity 2, «ha avisat» booking " + booking(duna).getString("state"));
                assertThat(outcomes.getFirst()).isEqualTo("save 200");
                assertThat(live).as("zero overbooking").isLessThanOrEqualTo(2);
                assertThat(session("race").get("counters", Document.class).getInteger("booked")).isEqualTo((int) live);
                assertThat(booking(duna).getString("state")).isEqualTo("CANCELLED");
                long claimed = outcomes.stream().filter(o -> o.equals("claim 201")).count();
                assertThat(claimed).as("both freed seats may be taken, never more").isLessThanOrEqualTo(2).isEqualTo(live);
                // After the commit the seat «ha avisat» freed is visible: the entry that lost the race is offered it again and takes it.
                var losers = new ArrayList<String[]>();
                if (!outcomes.get(1).equals("claim 201")) { losers.add(new String[] {"joan", "s08-d-toby", toby}); }
                if (!outcomes.get(2).equals("claim 201")) { losers.add(new String[] {"c0", "s08-d-c0", dog0}); }
                dispatch();
                for (var loser : losers) {
                    var hold = holdFor(as(loser[0]), "race", loser[1], loser[2], 201);
                    claim(as(loser[0]), loser[2], hold.path("id").asText(), null, 201);
                }
                assertThat(count("bookings", Criteria.where("classSessionId").is("s08-race").and("state").in("ACTIVE", "PAYMENT_PENDING"))).isEqualTo(2);
                assertThat(session("race").get("counters", Document.class).getInteger("booked")).isEqualTo(2);
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void T_10_08_claimForNoShowNoticeTakesEarlierNoShowsOnceAndLeavesTodaysAndUnmarkedOnes() throws Exception {
        var duna = id(book(as("laura"), "wed", "s08-d-duna"));
        var toby = id(book(as("joan"), "wed", "s08-d-toby"));
        var nit = id(book(as("pere"), "wed", "s08-d-nit"));
        var dog0 = id(book(as("c0"), "thu", "s08-d-c0"));
        clock.setInstant(local("2026-10-07T20:00"));
        save("wed", as("inst"), 200, 0, duna, "NO_SHOW", toby, "NO_SHOW", nit, "NO_SHOW");
        save("wed", as("inst"), 200, 1, toby, "PRESENT"); // unmarked before the batch: no notice
        clock.setInstant(local("2026-10-08T08:00"));
        var sheetBefore = sheet("wed", as("inst"));
        assertThat(row(sheetBefore, duna).path("noShowNotice").path("scheduledFor").asText()).isEqualTo("2026-10-08T06:00:00Z");
        assertThat(row(sheetBefore, duna).path("noShowNotice").path("queuedAt").isNull()).isTrue();
        NoShowNoticeClaims.Claim first, second;
        try (var tenant = TenantContext.open(CLUB)) { first = claims.claim(LocalDate.of(2026, 10, 8)); second = claims.claim(LocalDate.of(2026, 10, 8)); }
        assertThat(first.bookingIds()).containsExactlyInAnyOrder(duna, nit);
        assertThat(second.bookingIds()).isEmpty(); assertThat(second.eventId()).isNull();
        assertThat(eventsOf("NoShowNoticeDue")).singleElement().satisfies(e -> {
            assertThat(e.getString("_id")).isEqualTo(first.eventId());
            assertThat(e.get("payload", Document.class).getList("bookingIds", String.class)).containsExactlyInAnyOrder(duna, nit);
            assertThat(e.get("payload", Document.class).getList("attendanceIds", String.class)).containsExactlyInAnyOrderElementsOf(first.attendanceIds());
        });
        assertThat(attendance(duna).get("noShowNotice", Document.class)).containsEntry("eventId", first.eventId()).containsKey("queuedAt");
        assertThat(attendance(toby).get("noShowNotice")).isNull();
        assertThat(row(sheet("wed", as("inst")), duna).path("noShowNotice").path("queuedAt").asText()).isEqualTo("2026-10-08T06:00:00Z");
        // A NO_SHOW of today's class waits for tomorrow's run; marked late, it enters the next batch with its own class date.
        clock.setInstant(local("2026-10-08T20:00"));
        save("thu", as("inst"), 200, 0, dog0, "NO_SHOW");
        try (var tenant = TenantContext.open(CLUB)) { assertThat(claims.claim(LocalDate.of(2026, 10, 8)).bookingIds()).isEmpty(); }
        // `bin/core attendance:claim-no-show --club=…` runs the claim with the club-local date: Fri 09-10 claims Thursday's.
        clock.setInstant(local("2026-10-09T08:00"));
        String slug = clubs.findById(CLUB).orElseThrow().slug();
        command.run(new org.springframework.boot.DefaultApplicationArguments("--core.command=attendance:claim-no-show", "--club=" + slug));
        assertThat(attendance(dog0).get("noShowNotice", Document.class)).containsKey("queuedAt");
        assertThat(events("NoShowNoticeDue")).isEqualTo(2);
        command.run(new org.springframework.boot.DefaultApplicationArguments("--core.command=attendance:claim-no-show", "--club=" + slug));
        assertThat(events("NoShowNoticeDue")).isEqualTo(2);
        assertThatThrownBy(() -> command.run(new org.springframework.boot.DefaultApplicationArguments("--club=missing")))
                .isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class);
        assertThatThrownBy(() -> command.run(new org.springframework.boot.DefaultApplicationArguments("unexpected"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void T_10_22_rolesImpersonationAndGlobalVisionOnBothAttendanceVerbs() throws Exception {
        account("inst2", "INSTRUCTOR", "s08-m-inst2"); member("s08-m-inst2", "inst2", "Neus", "ca");
        var duna = id(book(as("laura"), "wed", "s08-d-duna"));
        clock.setInstant(local("2026-10-07T19:00"));
        assertThat(code(call(HttpMethod.GET, "/class-sessions/s08-wed/attendance", null, as("laura"), 403))).isEqualTo("FORBIDDEN");
        assertThat(code(save("wed", as("laura"), 403, 0, duna, "PRESENT"))).isEqualTo("FORBIDDEN");
        var impersonated = impersonating("admin", "s08-m-laura");
        assertThat(code(call(HttpMethod.GET, "/class-sessions/s08-wed/attendance", null, impersonated, 403))).isEqualTo("IMPERSONATION_DENIED");
        assertThat(code(save("wed", impersonated, 403, 0, duna, "PRESENT"))).isEqualTo("IMPERSONATION_DENIED");
        assertThat(code(call(HttpMethod.GET, "/class-sessions/s08-missing/attendance", null, as("inst"), 404))).isEqualTo("NOT_FOUND");
        // Global vision: Neus is not the class's instructor (Estela is) and marks it.
        save("wed", as("inst2"), 200, 0, duna, "PRESENT");
        assertThat(attendance(duna).get("markedBy", Document.class)).containsEntry("accountId", "s08-inst2").containsEntry("role", "INSTRUCTOR");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(OTHER)), "attendances")).isZero();
    }

    @Test @AuditCovers(AuditAction.ATTENDANCE_OVERRIDDEN)
    void T_10_01_anAdminMarksOutsideTheWindowAuditedAndAnInstructorIsRefused() throws Exception {
        var duna = id(book(as("laura"), "wed", "s08-d-duna"));
        var toby = id(book(as("joan"), "wed", "s08-d-toby"));
        // Tue 06-10 10:00, before T0 of Wed 07-10.
        assertThat(code(save("wed", as("inst"), 422, 0, duna, "PRESENT"))).isEqualTo("ATTENDANCE_NOT_OPEN");
        // An ADMIN's «ha avisat» (D12) is the same S08 call: origin INSTRUCTOR, `by: ADMIN`, and the member still gets N-05, not N-36.
        assertThat(save("wed", as("admin"), 200, 0, duna, "NOTIFIED").path("sheet").path("version").asLong()).as("NOTIFIED has no T0 bound").isEqualTo(1);
        assertThat(count("audit_entries", Criteria.where("action").is("ATTENDANCE_OVERRIDDEN"))).isZero();
        assertThat(booking(duna).get("cancelledBy", Document.class).getString("role")).isEqualTo("ADMIN");
        dispatch();
        assertThat(notifications("N-05", "APP")).isEqualTo(1); assertThat(notifications("N-36", null)).isZero();
        save("wed", as("admin"), 200, 1, toby, "PRESENT");
        var entry = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("ATTENDANCE_OVERRIDDEN")), Document.class, "audit_entries");
        assertThat(entry.getString("entityType")).isEqualTo("Attendance");
        assertThat(entry.getString("entityId")).isEqualTo(attendance(toby).getString("_id"));
        assertThat(entry.getString("memberId")).isEqualTo("s08-m-joan");
        assertThat(entry.getList("changes", Document.class)).extracting(c -> c.getString("path")).contains("state", "bookingId", "classSessionId");
        // Thu 08-10 23:59:59 is T1; Fri 09-10 00:00 is past it.
        clock.setInstant(local("2026-10-09T00:00"));
        var closed = save("wed", as("inst"), 422, 2, toby, "NO_SHOW");
        assertThat(code(closed)).isEqualTo("ATTENDANCE_WINDOW_CLOSED");
        assertThat(closed.path("details").path("editableUntil").asText()).isEqualTo("2026-10-08T21:59:59Z");
        save("wed", as("admin"), 200, 2, toby, "NO_SHOW");
        assertThat(count("audit_entries", Criteria.where("action").is("ATTENDANCE_OVERRIDDEN"))).isEqualTo(2);
        assertThat(code(save("wed", as("admin"), 422, 3, duna, "PRESENT"))).isEqualTo("ATTENDANCE_NOTIFIED_FINAL");
    }

    @Test void R_08_10_aMarkedBookingIsNoLongerCancellableAndANoShowReadsNoPresentat() throws Exception {
        var duna = id(book(as("laura"), "wed", "s08-d-duna"));
        var toby = id(book(as("joan"), "wed", "s08-d-toby"));
        clock.setInstant(local("2026-10-07T19:00"));
        save("wed", as("inst"), 200, 0, duna, "PRESENT", toby, "NO_SHOW");
        assertThat(code(cancel(as("laura"), duna, 422))).isEqualTo("BOOKING_NOT_CANCELLABLE");
        assertThat(code(cancel(as("joan"), toby, 422))).isEqualTo("BOOKING_NOT_CANCELLABLE");
        assertThat(call(HttpMethod.GET, "/bookings/" + toby, null, as("joan"), 200).path("displayState").asText()).isEqualTo("NO_SHOW");
        // S10 §5: PRESENT → NOTIFIED before the class end is a transition; S08 cancels despite the stored mark.
        assertThat(row(save("wed", as("inst"), 200, 1, duna, "NOTIFIED"), duna).path("notice").path("bookingState").asText()).isEqualTo("CANCELLED_LATE");
    }

    @Test void T_10_10_consumersRefreshTheClassTimesAndRecordAContradictingCancellationOnce() throws Exception {
        var duna = id(book(as("laura"), "wed", "s08-d-duna"));
        clock.setInstant(local("2026-10-07T19:00"));
        save("wed", as("inst"), 200, 0, duna, "PRESENT");
        var moved = local("2026-10-07T19:10");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-wed")), new Update().set("startsAt", moved).set("endsAt", moved.plusSeconds(3600)).set("startTime", "19:10"), "class_sessions");
        var updated = new ForeignEvent("ClassSessionUpdated", null, CLUB, "ClassSession", "s08-wed", clock.instant(), Map.of("classId", "s08-wed"), null, null, DomainEvent.Origin.BACKOFFICE);
        consumers.classUpdated(updated); consumers.classUpdated(updated);
        assertThat(attendance(duna).getDate("classStartsAt").toInstant()).isEqualTo(moved);
        assertThat(attendance(duna).getString("classDate")).isEqualTo("2026-10-07");
        var cancelled = new BookingEvent(BookingEvent.Kind.BookingCancelled, CLUB, duna, clock.instant(), Map.of("bookingId", duna, "by", "SYSTEM", "origin", "SYSTEM"),
                null, null, DomainEvent.Origin.SYSTEM);
        consumers.bookingCancelled(cancelled); consumers.bookingCancelled(cancelled);
        assertThat(attendance(duna).getList("history", Document.class)).hasSize(2).extracting(h -> h.getString("state")).containsExactly("PRESENT", "PRESENT");
        assertThat(attendance(duna).getString("state")).isEqualTo("PRESENT");
        // An instructor's «ha avisat» cancellation is S10's own: nothing to record.
        consumers.bookingCancelled(new BookingEvent(BookingEvent.Kind.BookingCancelled, CLUB, duna, clock.instant(), Map.of("by", "INSTRUCTOR", "origin", "INSTRUCTOR"),
                "s08-inst", null, DomainEvent.Origin.INSTRUCTOR));
        assertThat(attendance(duna).getList("history", Document.class)).hasSize(2);
    }
}
