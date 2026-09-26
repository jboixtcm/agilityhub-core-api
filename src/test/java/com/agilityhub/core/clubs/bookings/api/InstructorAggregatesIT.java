package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.platform.application.Module;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.*;


/**
 * S10 WP-10-B read aggregates (E6-T02): the instructor day (20, R-10-01), the week agenda and its PDF (D12, R-10-15),
 * the student card (22/D13, R-10-08/09), the member history (25, R-10-14), `GET /attendances` and the club variants of
 * R-10-16, over the fictional S08 club. Past data (bookings, marks, trainings, an activity) is seeded as documents.
 */
class InstructorAggregatesIT extends BookingFixtures {
    @BeforeEach void s10Fixtures() {
        for (String collection : List.of("activities", "activity_registrations")) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        mongo.save(new Document("_id", "s08-instructor-anna").append("clubId", CLUB).append("memberId", "s08-m-anna").append("shortName", "Anna").append("color", "#654321")
                .append("active", true).append("version", 0), "instructors");
        mongo.save(new Document("_id", "s08-instructor-old").append("clubId", CLUB).append("memberId", "s08-m-old").append("shortName", "Aaron").append("color", "#654321")
                .append("active", false).append("version", 0), "instructors");
        account("anna", "INSTRUCTOR", "s08-m-anna"); member("s08-m-anna", "anna", "Anna", "ca");
        mongo.save(new Document("_id", "s08-ring2").append("clubId", CLUB).append("name", "Muntanya").append("shortName", "MUN").append("color", "#F2B58C")
                .append("allowsFreeTraining", true).append("active", true).append("order", 2).append("version", 0), "rings");
    }

    /** A class with its own instructors, ring, capacity and state (the fixture's `session` has Estela, Central, ACTIVE). */
    void classOf(String id, String start, int capacity, List<String> instructors, String ring, String state) {
        session(id, start, capacity, List.of());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-" + id)), new Update().set("instructorIds", instructors).set("ringId", ring).set("state", state), "class_sessions");
    }
    void block(String id, String ring, String from, String to, String reason, String note) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("ringId", ring).append("from", local(from)).append("to", local(to)).append("kind", "BLOCK")
                .append("reason", reason).append("note", note).append("state", "ACTIVE").append("createdByAccountId", "s08-admin").append("version", 0), "ring_blocks");
    }
    void training(String id, String dog, String member, String ring, String start, String state) {
        var starts = local(start);
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("memberId", member).append("dogId", dog).append("ringId", ring).append("startsAt", starts)
                .append("endsAt", starts.plusSeconds(1800)).append("slotId", id).append("seatIndex", 0).append("weekStart", starts).append("state", state)
                .append("origin", "APP").append("version", 0), "training_bookings");
    }
    /** A past booking and, unless PENDING, its attendance (the card and history read both). */
    String past(String classId, String dog, String member, String bookingState, String attendance, String reason, boolean afterClassEnd) {
        var cls = session(classId);
        String id = "s08-bk-" + classId + "-" + dog;
        var booking = new Document("_id", id).append("clubId", CLUB).append("classSessionId", cls.getString("_id")).append("dogId", dog).append("memberId", member)
                .append("state", bookingState).append("origin", "APP").append("bookedAt", cls.getDate("startsAt").toInstant().minusSeconds(86_400 * 3))
                .append("classStartsAt", cls.getDate("startsAt").toInstant()).append("classEndsAt", cls.getDate("endsAt").toInstant()).append("bookingWeekKey", "w").append("version", 0);
        if (!bookingState.equals("ACTIVE")) {
            booking.append("cancelledAt", cls.getDate("startsAt").toInstant().minusSeconds(3600)).append("cancelReason", reason)
                    .append("cancelledBy", new Document("role", "MEMBER")).append("late", bookingState.equals("CANCELLED_LATE")).append("cancelMessage", "Pluja forta");
        }
        mongo.save(booking, "bookings");
        if (attendance != null) {
            var mark = new Document("_id", "s08-att-" + id).append("clubId", CLUB).append("bookingId", id).append("classSessionId", cls.getString("_id"))
                    .append("classDate", cls.getString("date")).append("classStartsAt", cls.getDate("startsAt").toInstant()).append("classEndsAt", cls.getDate("endsAt").toInstant())
                    .append("dogId", dog).append("memberId", member).append("state", attendance).append("markedAt", cls.getDate("endsAt").toInstant())
                    .append("markedBy", new Document("accountId", "s08-inst").append("role", "INSTRUCTOR").append("displayName", "Estela")).append("history", List.of()).append("version", 0);
            if (attendance.equals("NOTIFIED")) {
                mark.append("notice", new Document("at", cls.getDate("startsAt").toInstant().minusSeconds(afterClassEnd ? -7200 : 7200)).append("late", afterClassEnd)
                        .append("seatReleased", !afterClassEnd).append("waitlistNotified", false).append("afterClassEnd", afterClassEnd).append("bookingState", bookingState));
            }
            mongo.save(mark, "attendances");
        }
        return id;
    }
    JsonNode fetch(String path, RequestPostProcessor auth, int expected, String... params) throws Exception {
        var query = new StringBuilder(); for (int i = 0; i < params.length; i += 2) { query.append(i == 0 ? "?" : "&").append(params[i]).append("=").append(params[i + 1]); }
        return call(HttpMethod.GET, path + query, null, auth, expected);
    }
    static List<String> texts(JsonNode array, String field) { var out = new ArrayList<String>(); array.forEach(n -> out.add(n.path(field).asText())); return out; }

    @Test void T_10_09_theDayPreselectsTheCallerListsOnlyTheirClassesAndEveryBlock() throws Exception {
        classOf("anna", "2026-10-07T10:00", 5, List.of("s08-instructor-anna"), "s08-ring", "ACTIVE");
        classOf("draft", "2026-10-07T11:00", 5, List.of("s08-instructor"), "s08-ring", "DRAFT");
        block("s08-block", "s08-ring2", "2026-10-07T16:00", "2026-10-07T17:30", "MAINTENANCE", "regar i repassar el terra");
        var duna = book(as("laura"), "wed", "s08-d-duna").path("id").asText();
        var toby = book(as("joan"), "wed", "s08-d-toby").path("id").asText();
        clock.setInstant(local("2026-10-07T12:00"));
        var day = fetch("/instructor/day", as("inst"), 200, "date", "2026-10-07");
        assertThat(day.path("selectedInstructorId").asText()).isEqualTo("s08-instructor");
        assertThat(day.path("timeZone").asText()).isEqualTo("Europe/Madrid");
        assertThat(texts(day.path("instructors"), "shortName")).as("active instructors by shortName, no «Tot el club»").containsExactly("Anna", "Estela");
        assertThat(texts(day.path("days"), "date")).containsExactly("2026-10-07", "2026-10-08", "2026-10-09", "2026-10-10", "2026-10-11", "2026-10-12", "2026-10-13");
        assertThat(day.path("days").findValues("hasClasses").stream().map(JsonNode::asBoolean).toList()).containsExactly(true, true, true, true, false, true, true);
        assertThat(texts(day.path("classes"), "id")).containsExactly("s08-wed");
        var wed = day.path("classes").get(0);
        assertThat(wed.path("startTime").asText()).isEqualTo("18:50"); assertThat(wed.path("ring").path("color").asText()).isEqualTo("#8FCE8F");
        assertThat(wed.path("booked").asInt()).isEqualTo(2); assertThat(wed.path("individual").asBoolean()).isFalse(); assertThat(wed.path("waiting").asInt()).isZero();
        assertThat(wed.path("attendance").path("status").asText()).isEqualTo("PENDING");
        assertThat(wed.path("attendance").path("total").asInt()).isEqualTo(2);
        assertThat(day.path("ringBlocks")).singleElement().satisfies(b -> {
            assertThat(b.path("ringName").asText()).isEqualTo("Muntanya"); assertThat(b.path("fromLocal").asText()).isEqualTo("16:00");
            assertThat(b.path("toLocal").asText()).isEqualTo("17:30"); assertThat(b.path("reason").asText()).isEqualTo("MAINTENANCE");
            assertThat(b.path("note").asText()).isEqualTo("regar i repassar el terra"); assertThat(b.path("createdByName").asText()).isEqualTo("Example admin");
        });
        // Tomorrow: before T0 (NONE) and the individual class (capacity 1).
        var thursday = fetch("/instructor/day", as("inst"), 200, "date", "2026-10-08");
        assertThat(thursday.path("classes").findValues("individual").stream().map(JsonNode::asBoolean).toList()).contains(true);
        assertThat(thursday.path("classes").findValuesAsText("status")).containsOnly("NONE");
        // Every row marked → DONE; after T1 → CLOSED.
        call(HttpMethod.PUT, "/class-sessions/s08-wed/attendance", Map.of("version", 0, "items", List.of(Map.of("bookingId", duna, "state", "PRESENT"))), as("inst"), 200,
                UUID.randomUUID().toString());
        assertThat(fetch("/instructor/day", as("inst"), 200).path("classes").get(0).path("attendance").path("marked").asInt()).isEqualTo(1);
        call(HttpMethod.PUT, "/class-sessions/s08-wed/attendance", Map.of("version", 1, "items", List.of(Map.of("bookingId", toby, "state", "NO_SHOW"))), as("inst"), 200,
                UUID.randomUUID().toString());
        assertThat(fetch("/instructor/day", as("inst"), 200).path("classes").get(0).path("attendance").path("status").asText()).isEqualTo("DONE");
        clock.setInstant(local("2026-10-09T08:00"));
        assertThat(fetch("/instructor/day", as("inst"), 200, "date", "2026-10-07").path("classes").get(0).path("attendance").path("status").asText()).isEqualTo("CLOSED");
        clock.setInstant(local("2026-10-07T12:00"));
        // An ADMIN without a profile gets the first active instructor by shortName; `me` and another instructor are explicit.
        var admin = fetch("/instructor/day", as("admin"), 200, "date", "2026-10-07");
        assertThat(admin.path("selectedInstructorId").asText()).isEqualTo("s08-instructor-anna");
        assertThat(texts(admin.path("classes"), "id")).containsExactly("s08-anna");
        assertThat(admin.path("ringBlocks").size()).isEqualTo(1);
        assertThat(fetch("/instructor/day", as("anna"), 200, "date", "2026-10-07", "instructorId", "me").path("selectedInstructorId").asText()).isEqualTo("s08-instructor-anna");
        assertThat(texts(fetch("/instructor/day", as("anna"), 200, "date", "2026-10-07", "instructorId", "s08-instructor").path("classes"), "id")).containsExactly("s08-wed");
        // WAITLIST off: no «⏳ n».
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.WAITLIST).toArray(Module[]::new));
        assertThat(fetch("/instructor/day", as("inst"), 200, "date", "2026-10-07").path("classes").get(0).has("waiting")).isFalse();
        assertThat(code(fetch("/instructor/day", as("laura"), 403))).isEqualTo("FORBIDDEN");
        assertThat(code(fetch("/instructor/day", impersonating("admin", "s08-m-laura"), 403))).isEqualTo("IMPERSONATION_DENIED");
    }

    @Test void T_10_20_theWeekIsTheIsoWeekWithClassesTrainingsAndBlocksAndItsFiltersAndPdf() throws Exception {
        classOf("anna", "2026-10-07T10:00", 5, List.of("s08-instructor-anna"), "s08-ring2", "ACTIVE");
        classOf("draft", "2026-10-07T11:00", 5, List.of("s08-instructor"), "s08-ring", "DRAFT");
        classOf("cancelled", "2026-10-09T10:00", 5, List.of("s08-instructor"), "s08-ring", "CANCELLED");
        training("s08-tb1", "s08-d-rock", "s08-m-laura", "s08-ring2", "2026-10-05T08:00", "ACTIVE");
        training("s08-tb2", "s08-d-toby", "s08-m-joan", "s08-ring2", "2026-10-05T09:00", "CANCELLED");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-d-rock")), new Update().set("handlerName", "Pau"), "dogs");
        block("s08-block", "s08-ring", "2026-10-07T16:00", "2026-10-07T18:00", "MAINTENANCE", null);
        clock.setInstant(local("2026-10-07T12:00"));
        var week = fetch("/instructor/week", as("inst"), 200, "date", "2026-10-07");
        assertThat(week.path("week").path("startDate").asText()).isEqualTo("2026-10-05");
        assertThat(week.path("week").path("endDate").asText()).as("Monday–Saturday without Sunday items").isEqualTo("2026-10-10");
        assertThat(week.path("week").path("relative").asText()).isEqualTo("CURRENT");
        var cells = week.path("cells");
        var classes = new ArrayList<String>(); var kinds = new ArrayList<String>();
        cells.forEach(c -> { kinds.add(c.path("kind").asText()); if (c.path("kind").asText().equals("CLASS")) { classes.add(c.path("classId").asText()); } });
        assertThat(classes).contains("s08-wed", "s08-anna", "s08-cancelled").doesNotContain("s08-draft");
        var training = cells.findParents("trainingBookingId").getFirst();
        assertThat(training.path("time").asText()).isEqualTo("08:00"); assertThat(training.path("endTime").asText()).as("training.slotMinutes, half height").isEqualTo("08:30");
        assertThat(training.path("who").asText()).as("R-10-00: the handler when the dog has one").isEqualTo("Pau + Rock");
        assertThat(cells.findValuesAsText("trainingBookingId")).containsExactly("s08-tb1");
        var block = cells.findParents("blockId").getFirst();
        assertThat(block.path("reason").asText()).isEqualTo("MAINTENANCE"); assertThat(block.path("createdByName").asText()).isEqualTo("Example admin");
        var wed = cells.findParents("classId").stream().filter(c -> c.path("classId").asText().equals("s08-wed")).findFirst().orElseThrow();
        assertThat(wed.path("instructorName").asText()).isEqualTo("Estela"); assertThat(wed.path("attendanceStatus").asText()).isEqualTo("DONE");
        assertThat(wed.path("ringColor").asText()).isEqualTo("#8FCE8F"); assertThat(wed.has("waiting")).isTrue();
        var cancelled = cells.findParents("classId").stream().filter(c -> c.path("classId").asText().equals("s08-cancelled")).findFirst().orElseThrow();
        assertThat(cancelled.path("state").asText()).isEqualTo("CANCELLED"); assertThat(cancelled.path("attendanceStatus").asText()).isEqualTo("CLOSED");
        assertThat(week.path("rows").toString()).as("the distinct start times, sorted").startsWith("[\"08:00\"").contains("\"10:00\"", "\"16:00\"", "\"18:50\"");
        assertThat(texts(week.path("filters").path("instructors"), "shortName")).containsExactly("Anna", "Estela");
        assertThat(texts(week.path("filters").path("rings"), "name")).containsExactly("Central", "Muntanya");
        // instructorId narrows the classes only; `me` is the caller.
        var mine = fetch("/instructor/week", as("anna"), 200, "date", "2026-10-07", "instructorId", "me");
        assertThat(mine.path("filters").path("instructorId").asText()).isEqualTo("s08-instructor-anna");
        assertThat(mine.path("cells").findValuesAsText("classId")).containsExactly("s08-anna");
        assertThat(mine.path("cells").findValuesAsText("trainingBookingId")).containsExactly("s08-tb1");
        assertThat(mine.path("cells").findValuesAsText("blockId")).containsExactly("s08-block");
        // ringId narrows everything.
        var central = fetch("/instructor/week", as("inst"), 200, "date", "2026-10-07", "ringId", "s08-ring");
        assertThat(central.path("cells").findValuesAsText("classId")).doesNotContain("s08-anna");
        assertThat(central.path("cells").findValuesAsText("trainingBookingId")).isEmpty();
        assertThat(central.path("cells").findValuesAsText("blockId")).containsExactly("s08-block");
        // A Sunday item shows Sunday.
        classOf("sunday", "2026-10-11T10:00", 5, List.of("s08-instructor"), "s08-ring", "ACTIVE");
        assertThat(fetch("/instructor/week", as("inst"), 200, "date", "2026-10-07").path("week").path("endDate").asText()).isEqualTo("2026-10-11");
        assertThat(fetch("/instructor/week", as("inst"), 200, "date", "2026-09-30").path("week").path("relative").asText()).isEqualTo("PAST");
        assertThat(fetch("/instructor/week", as("inst"), 200, "date", "2026-10-12").path("week").path("relative").asText()).isEqualTo("FUTURE");

        // The PDF: the same query, landscape, labels in the caller's locale, the club's slug and the week's Monday in the name.
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/instructor/week/export").param("format", "pdf").param("date", "2026-10-07").header("Host", HOST)
                .header("Accept-Language", "ca").with(as("inst"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200); assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader("Content-Disposition")).contains("attachment").contains(clubs.findById(CLUB).orElseThrow().slug() + "_agenda_20261005.pdf");
        try (var pdf = Loader.loadPDF(response.getContentAsByteArray())) {
            assertThat(pdf.getNumberOfPages()).isPositive();
            var page = pdf.getPage(0).getMediaBox();
            assertThat(page.getWidth()).as("landscape").isGreaterThan(page.getHeight());
            // A cell's label wraps inside its column: compare with the line breaks folded into spaces.
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
            System.out.println("T-10-20 agenda PDF: " + pdf.getNumberOfPages() + " page(s), " + page.getWidth() + "×" + page.getHeight() + " pt; head: "
                    + text.lines().limit(4).toList());
            assertThat(text).contains("Agenda de la setmana", "Hora", "dl 5/10", "ds 10/10", "Reserva Muntanya — Pau + Rock", "Bloqueig Central — manteniment",
                    "Pàgina 1 de " + pdf.getNumberOfPages());
        }
        var english = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/instructor/week/export").param("format", "pdf").param("date", "2026-10-07").header("Host", HOST)
                .header("Accept-Language", "en").with(as("inst"))).andReturn().getResponse();
        try (var pdf = Loader.loadPDF(english.getContentAsByteArray())) { assertThat(new PDFTextStripper().getText(pdf)).contains("Weekly agenda", "Mon 5/10"); }

        // FREE_TRAINING off: no TRAINING cell.
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.FREE_TRAINING).toArray(Module[]::new));
        assertThat(fetch("/instructor/week", as("inst"), 200, "date", "2026-10-07").path("cells").findValuesAsText("trainingBookingId")).isEmpty();
    }

    @Test void T_10_20_theIsoWeekIsTheClubsInMadridAndBuenosAires() throws Exception {
        // 2026-10-05T01:30Z is Monday 03:30 in Madrid but Sunday 22:30 in Buenos Aires.
        classOf("edge", "2026-10-05T03:30", 5, List.of("s08-instructor"), "s08-ring", "ACTIVE");
        clock.setInstant(local("2026-10-07T12:00"));
        assertThat(fetch("/instructor/week", as("inst"), 200, "date", "2026-10-05").path("cells").findValuesAsText("classId")).contains("s08-edge");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().set("timeZone", "America/Argentina/Buenos_Aires"),
                com.agilityhub.core.platform.persistence.Club.class);
        configs.invalidate(CLUB);
        var buenosAires = fetch("/instructor/week", as("inst"), 200, "date", "2026-10-05");
        assertThat(buenosAires.path("week").path("startDate").asText()).isEqualTo("2026-10-05");
        assertThat(buenosAires.path("cells").findValuesAsText("classId")).doesNotContain("s08-edge");
        assertThat(fetch("/instructor/week", as("inst"), 200, "date", "2026-10-04").path("cells").findValuesAsText("classId")).contains("s08-edge");
        assertThat(fetch("/instructor/day", as("inst"), 200).path("timeZone").asText()).isEqualTo("America/Argentina/Buenos_Aires");
    }

    @Test void T_10_14_theCardComputesTheThirtyDayMetricsTheLastClassesAndTheModuleBlocks() throws Exception {
        clock.setInstant(local("2026-10-06T10:00"));
        for (int i = 1; i <= 9; i++) { session("p" + i, "2026-09-" + String.format("%02d", 8 + i * 2) + "T18:50", 5, List.of()); }
        session("old", "2026-08-20T18:50", 5, List.of());
        for (int i = 1; i <= 6; i++) { past("p" + i, "s08-d-duna", "s08-m-laura", "ACTIVE", "PRESENT", null, false); }
        past("p7", "s08-d-duna", "s08-m-laura", "ACTIVE", "NO_SHOW", null, false);
        past("p8", "s08-d-duna", "s08-m-laura", "CANCELLED", "NOTIFIED", "INSTRUCTOR_NOTICE", false);
        past("p9", "s08-d-duna", "s08-m-laura", "CANCELLED_BY_CLUB", null, "CLUB_CLASS_CANCELLED", false);
        past("old", "s08-d-duna", "s08-m-laura", "ACTIVE", "PRESENT", null, false);
        for (int i = 0; i < 10; i++) { training("s08-tr" + i, "s08-d-duna", "s08-m-laura", "s08-ring", "2026-09-" + (10 + i * 2) + "T08:00", "ACTIVE"); }
        training("s08-tr-cancelled", "s08-d-duna", "s08-m-laura", "s08-ring", "2026-09-29T09:00", "CANCELLED");
        training("s08-tr-rock", "s08-d-rock", "s08-m-laura", "s08-ring", "2026-09-29T10:00", "ACTIVE");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-d-duna")), new Update().set("levelAssignedAt", Instant.parse("2025-12-03T09:00:00Z"))
                .set("breed", "Border collie").set("birthDate", LocalDate.of(2022, 5, 1)).set("remarks", "private-observation-text").set("photoFileKey", "dogs/duna.jpg"), "dogs");
        var card = fetch("/dogs/s08-d-duna/instructor-card", as("inst"), 200);
        assertThat(card.path("metrics").path("attendancePct").asInt()).isEqualTo(86);
        assertThat(card.path("metrics").path("classesCounted").asInt()).isEqualTo(7);
        assertThat(card.path("metrics").path("present").asInt()).isEqualTo(6); assertThat(card.path("metrics").path("noShow").asInt()).isEqualTo(1);
        assertThat(card.path("metrics").path("notified").asInt()).isEqualTo(1); assertThat(card.path("metrics").path("cancelledLate").asInt()).isZero();
        assertThat(card.path("metrics").path("windowDays").asInt()).isEqualTo(30);
        assertThat(card.path("metrics").path("trainingsCount").asInt()).as("per dog, ACTIVE, ended in the window").isEqualTo(10);
        assertThat(card.path("metrics").path("trainingsPerWeek").asDouble()).isEqualTo(2.3);
        assertThat(card.path("lastClasses").findValuesAsText("displayState")).containsExactly("NOTIFIED", "NO_SHOW", "PRESENT", "PRESENT", "PRESENT");
        assertThat(card.path("lastClasses").get(0).path("date").asText()).isEqualTo("2026-09-24");
        assertThat(card.path("lastClasses").get(0).path("instructorName").asText()).isEqualTo("Estela");
        assertThat(card.path("lastClasses").get(0).path("ringName").asText()).isEqualTo("Central");
        assertThat(card.path("level").path("code").asText()).isEqualTo("C"); assertThat(card.path("level").path("assignedAt").asText()).isEqualTo("2025-12-03T09:00:00Z");
        assertThat(card.path("dog").path("breed").asText()).isEqualTo("Border collie"); assertThat(card.path("dog").path("ageYears").asInt()).isEqualTo(4);
        assertThat(card.path("dog").path("photoUrl").asText()).isNotBlank();
        assertThat(card.path("member").path("fullName").asText()).isEqualTo("Laura Example");
        assertThat(card.path("member").path("displayStatus").path("kind").asText()).isEqualTo("ACTIVE");
        assertThat(card.has("instructorNote") || card.has("tasks") || card.has("observations")).as("E6-T03 serves the follow-up blocks").isFalse();
        assertThat(card.toString()).doesNotContain("private-observation-text");
        assertThat(call(HttpMethod.GET, "/me/dogs", null, as("laura"), 200).toString()).doesNotContain("private-observation-text");
        // + a late cancellation and a «ha avisat» after the end: both count as cancelledLate → 6/9.
        session("q1", "2026-10-02T18:50", 5, List.of()); session("q2", "2026-10-03T18:50", 5, List.of());
        past("q1", "s08-d-duna", "s08-m-laura", "CANCELLED_LATE", null, "MEMBER", false);
        past("q2", "s08-d-duna", "s08-m-laura", "ACTIVE", "NOTIFIED", null, true);
        var more = fetch("/dogs/s08-d-duna/instructor-card", as("admin"), 200).path("metrics");
        assertThat(more.path("cancelledLate").asInt()).isEqualTo(2); assertThat(more.path("classesCounted").asInt()).isEqualTo(9);
        assertThat(more.path("attendancePct").asInt()).isEqualTo(67);
        // Nothing to count → null; FREE_TRAINING off → no training metrics; levels off → no level.
        assertThat(fetch("/dogs/s08-d-toby/instructor-card", as("inst"), 200).path("metrics").path("attendancePct").isNull()).isTrue();
        parameter("levels.enabled", false);
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.FREE_TRAINING).toArray(Module[]::new));
        var off = fetch("/dogs/s08-d-duna/instructor-card", as("inst"), 200);
        assertThat(off.has("level")).isFalse(); assertThat(off.path("metrics").has("trainingsCount")).isFalse(); assertThat(off.path("metrics").has("trainingsPerWeek")).isFalse();
        assertThat(code(fetch("/dogs/s08-d-other/instructor-card", as("inst"), 404))).isEqualTo("NOT_FOUND");
        assertThat(code(fetch("/dogs/s08-d-duna/instructor-card", as("laura"), 403))).isEqualTo("FORBIDDEN");
    }

    @Test void T_10_19_theHistoryListsOwnAndGroupDogsWithTheR1014StatesAndTheModuleTypes() throws Exception {
        clock.setInstant(local("2026-10-06T10:00"));
        session("h1", "2026-09-28T18:50", 5, List.of()); session("h2", "2026-09-21T18:50", 5, List.of()); session("h3", "2026-09-14T18:50", 5, List.of());
        session("h4", "2026-07-20T18:50", 5, List.of());
        var done = past("h1", "s08-d-duna", "s08-m-laura", "ACTIVE", "PRESENT", null, false);
        var noShow = past("h2", "s08-d-duna", "s08-m-laura", "ACTIVE", "NO_SHOW", null, false);
        var byClub = past("h3", "s08-d-rock", "s08-m-laura", "CANCELLED_BY_CLUB", null, "CLUB_CLASS_CANCELLED", false);
        var groupDog = past("h1", "s08-d-toby", "s08-m-joan", "CANCELLED_LATE", null, "MEMBER", false);
        past("h4", "s08-d-duna", "s08-m-laura", "ACTIVE", "PRESENT", null, false); // older than history.monthsVisible (2)
        var live = book(as("laura"), "mon", "s08-d-duna").path("id").asText();
        var futureCancelled = book(as("laura"), "sat", "s08-d-rock").path("id").asText();
        cancel(as("laura"), futureCancelled, 200);
        training("s08-th1", "s08-d-rock", "s08-m-laura", "s08-ring", "2026-09-25T08:00", "ACTIVE");
        training("s08-th2", "s08-d-duna", "s08-m-laura", "s08-ring", "2026-09-26T08:00", "CANCELLED");
        training("s08-th3", "s08-d-duna", "s08-m-laura", "s08-ring", "2026-10-09T08:00", "ACTIVE"); // live: on 03
        mongo.save(new Document("_id", "s08-act").append("clubId", CLUB).append("title", new Document("values", new Document("ca", "Seminari d'obstacles")).append("defaultLocale", "ca"))
                .append("type", "SEMINAR").append("location", new Document("atClub", true)).append("ringIds", List.of()).append("date", "2026-09-27").append("startTime", "10:00")
                .append("endTime", "13:00").append("registrationFrom", "2026-09-01").append("registrationTo", "2026-09-26").append("levelIds", List.of())
                .append("waitlistEnabled", false).append("state", "FINISHED").append("counters", new Document("active", 1).append("waiting", 0)).append("version", 0), "activities");
        mongo.save(new Document("_id", "s08-reg").append("clubId", CLUB).append("activityId", "s08-act").append("memberId", "s08-m-laura").append("state", "ACTIVE")
                .append("origin", "APP").append("registeredAt", local("2026-09-02T10:00")).append("version", 0), "activity_registrations");
        var history = fetch("/me/history", as("laura"), 200);
        assertThat(history.path("monthsVisible").asInt()).isEqualTo(2); assertThat(history.path("from").asText()).isEqualTo("2026-08-06");
        assertThat(history.path("showDog").asBoolean()).isTrue();
        assertThat(texts(history.path("dogs"), "name")).containsExactly("Duna", "Rock", "Toby");
        var toby = history.path("dogs").get(2);
        assertThat(toby.path("own").asBoolean()).isFalse(); assertThat(toby.path("ownerFirstName").asText()).isEqualTo("Joan");
        assertThat(history.path("types").toString()).isEqualTo("[\"CLASS\",\"TRAINING\",\"ACTIVITY\"]");
        var ids = texts(history.path("items"), "id");
        assertThat(ids).containsExactly(futureCancelled, done, groupDog, "s08-reg", "s08-th2", "s08-th1", noShow, byClub).doesNotContain(live, "s08-th3");
        var items = new HashMap<String, JsonNode>(); history.path("items").forEach(i -> items.put(i.path("id").asText(), i));
        assertThat(items.get(done).path("state").asText()).isEqualTo("DONE"); assertThat(items.get(done).path("counts").asBoolean()).isTrue();
        assertThat(items.get(done).path("title").asText()).isEqualTo("Classe Classe h1"); assertThat(items.get(done).path("startsAtLocal").asText()).isEqualTo("2026-09-28T18:50");
        assertThat(items.get(noShow).path("detail").path("kind").asText()).isEqualTo("NO_SHOW");
        assertThat(items.get(byClub).path("state").asText()).isEqualTo("CANCELLED_BY_CLUB"); assertThat(items.get(byClub).path("detail").path("message").asText()).isEqualTo("Pluja forta");
        assertThat(items.get(groupDog).path("detail").path("kind").asText()).isEqualTo("BY_MEMBER"); assertThat(items.get(groupDog).path("detail").path("atLocal").asText()).isEqualTo("17:50");
        assertThat(items.get(futureCancelled).path("state").asText()).isEqualTo("CANCELLED"); assertThat(items.get(futureCancelled).path("counts").asBoolean()).isFalse();
        assertThat(items.get(futureCancelled).path("detail").path("kind").asText()).isEqualTo("BY_MEMBER_IN_TIME");
        assertThat(items.get("s08-th1").path("state").asText()).isEqualTo("DONE"); assertThat(items.get("s08-th1").path("title").asText()).isEqualTo("Entrenament");
        assertThat(items.get("s08-th2").path("state").asText()).isEqualTo("CANCELLED");
        assertThat(items.get("s08-reg").path("state").asText()).isEqualTo("DONE"); assertThat(items.get("s08-reg").path("dogId").isNull()).isTrue();
        // A group dog selected: only its items (activities are the member's, listed with «Tots» only).
        assertThat(texts(fetch("/me/history", as("laura"), 200, "dogId", "s08-d-toby").path("items"), "id")).containsExactly(groupDog);
        assertThat(texts(fetch("/me/history", as("laura"), 200, "type", "TRAINING").path("items"), "id")).containsExactly("s08-th2", "s08-th1");
        assertThat(fetch("/me/history", impersonating("admin", "s08-m-laura"), 200).path("items").size()).isEqualTo(8);
        // One dog: no chips.
        assertThat(fetch("/me/history", as("pere"), 200).path("showDog").asBoolean()).isFalse();
        assertThat(code(fetch("/me/history", as("pere"), 404, "dogId", "s08-d-duna"))).isEqualTo("DOG_NOT_ACCESSIBLE");
        assertThat(code(fetch("/me/history", as("inst"), 403))).isEqualTo("FORBIDDEN");
        // Modules off remove their types and items.
        modules(Module.WAITLIST, Module.FAQ, Module.PUSH);
        var minimal = fetch("/me/history", as("laura"), 200);
        assertThat(minimal.path("types").toString()).isEqualTo("[\"CLASS\"]");
        assertThat(texts(minimal.path("items"), "type")).containsOnly("CLASS");
        assertThat(minimal.path("showDog").asBoolean()).as("without FAMILY_GROUP only own dogs").isTrue();
        assertThat(texts(minimal.path("dogs"), "name")).containsExactly("Duna", "Rock");
    }

    @Test void T_10_33_clubVariantsLevelsOffFifoSharedClassesAndTheMinimalClub() throws Exception {
        var duna = book(as("laura"), "wed", "s08-d-duna").path("id").asText();
        book(as("joan"), "wed", "s08-d-toby"); book(as("pere"), "wed", "s08-d-nit");
        join(as("c0"), "wed", "s08-d-c0", 201);
        classOf("shared", "2026-10-08T10:00", 5, List.of("s08-instructor", "s08-instructor-anna"), "s08-ring", "ACTIVE");
        training("s08-tb1", "s08-d-rock", "s08-m-laura", "s08-ring2", "2026-10-05T08:00", "ACTIVE");
        clock.setInstant(local("2026-10-07T12:00"));
        parameter("levels.enabled", false); parameter("waitlist.mode", "FIFO"); parameter("classes.maxInstructorsPerClass", 2);
        var sheet = call(HttpMethod.GET, "/class-sessions/s08-wed/attendance", null, as("inst"), 200);
        assertThat(sheet.path("rows").findValues("levelCode").stream().allMatch(JsonNode::isNull)).isTrue();
        assertThat(sheet.path("waitlist").path("mode").asText()).isEqualTo("FIFO"); assertThat(sheet.path("waitlist").path("fifoConfirmMinutes").asInt()).isEqualTo(30);
        assertThat(sheet.path("waitlist").path("entries").get(0).path("levelCode").isNull()).isTrue();
        assertThat(fetch("/dogs/s08-d-duna/instructor-card", as("inst"), 200).has("level")).isFalse();
        var shared = fetch("/instructor/week", as("anna"), 200, "date", "2026-10-07", "instructorId", "me").path("cells").findParents("classId");
        assertThat(shared).singleElement().satisfies(c -> assertThat(c.path("instructorName").asText()).isEqualTo("Estela, Anna"));
        assertThat(fetch("/instructor/week", as("inst"), 200, "date", "2026-10-07", "instructorId", "me").path("cells").findValuesAsText("classId")).contains("s08-shared", "s08-wed");
        assertThat(texts(fetch("/instructor/day", as("anna"), 200, "date", "2026-10-08").path("classes"), "id")).containsExactly("s08-shared");
        // The «club mínim» (WAITLIST, FAQ, PUSH): 20, 21 and 25 answer; D12 has no TRAINING cell.
        modules(Module.WAITLIST, Module.FAQ, Module.PUSH);
        assertThat(fetch("/instructor/day", as("inst"), 200, "date", "2026-10-07").path("classes").size()).isEqualTo(1);
        assertThat(call(HttpMethod.GET, "/class-sessions/s08-wed/attendance", null, as("inst"), 200).path("waitlist").path("mode").asText()).isEqualTo("FIFO");
        assertThat(fetch("/me/history", as("laura"), 200).path("types").toString()).isEqualTo("[\"CLASS\"]");
        assertThat(fetch("/instructor/week", as("inst"), 200, "date", "2026-10-07").path("cells").findValuesAsText("trainingBookingId")).isEmpty();
        assertThat(duna).isNotBlank();
    }

    @Test void T_10_21_attendancesIsAUniversalListWithItsDeclaredFiltersOnly() throws Exception {
        var duna = book(as("laura"), "wed", "s08-d-duna").path("id").asText();
        var toby = book(as("joan"), "wed", "s08-d-toby").path("id").asText();
        var nit = book(as("pere"), "thu", "s08-d-nit").path("id").asText();
        clock.setInstant(local("2026-10-08T20:00"));
        call(HttpMethod.PUT, "/class-sessions/s08-wed/attendance", Map.of("version", 0, "items", List.of(Map.of("bookingId", duna, "state", "PRESENT"),
                Map.of("bookingId", toby, "state", "NO_SHOW"))), as("inst"), 200, UUID.randomUUID().toString());
        call(HttpMethod.PUT, "/class-sessions/s08-thu/attendance", Map.of("version", 0, "items", List.of(Map.of("bookingId", nit, "state", "NO_SHOW"))), as("admin"), 200,
                UUID.randomUUID().toString());
        var noShows = fetch("/attendances", as("admin"), 200, "filter", "state:eq:NO_SHOW", "sort", "classStartsAt,desc");
        assertThat(texts(noShows.path("items"), "bookingId")).containsExactly(nit, toby);
        assertThat(noShows.path("totalItems").asInt()).isEqualTo(2);
        var first = noShows.path("items").get(0);
        assertThat(first.path("dogName").asText()).isEqualTo("Nit"); assertThat(first.path("memberName").asText()).isEqualTo("Pere Example");
        assertThat(first.path("classDate").asText()).isEqualTo("2026-10-08"); assertThat(first.path("markedByName").asText()).isNotBlank();
        assertThat(texts(fetch("/attendances", as("inst"), 200, "filter", "classDate:between:2026-10-06,2026-10-07").path("items"), "bookingId")).containsExactlyInAnyOrder(duna, toby);
        assertThat(texts(fetch("/attendances", as("inst"), 200, "filter", "dogId:in:s08-d-duna,s08-d-nit", "sort", "classDate,asc").path("items"), "bookingId")).containsExactly(duna, nit);
        var sparse = fetch("/attendances", as("inst"), 200, "filter", "classSessionId:eq:s08-thu", "fields", "state");
        assertThat(sparse.path("items").get(0).toString()).isEqualTo("{\"id\":\"" + sparse.path("items").get(0).path("id").asText() + "\",\"state\":\"NO_SHOW\"}");
        assertThat(code(fetch("/attendances", as("inst"), 400, "filter", "markedByName:eq:x"))).isEqualTo("INVALID_FILTER");
        assertThat(code(fetch("/attendances", as("laura"), 403))).isEqualTo("FORBIDDEN");
    }
}
