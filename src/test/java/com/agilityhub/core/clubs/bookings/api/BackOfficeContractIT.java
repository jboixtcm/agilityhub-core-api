package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.support.SnapshotSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;

/**
 * E5-T29 over real Mongo: the S08 data the back office (D4, D10, D12, the instructor's drawer) and screens 07/29 need from
 * the api, found by the web's E5-W01…E5-W04 — the registrants' `displayState` and level, the staff waitlist's «{guia} +
 * {gos}», a class booking's description and ring, `GET /bookings/filter-values`, `nextBookableAt` as the start of the next
 * booking week, and the ring colour on every `Booking`. Every answer is checked against the committed snapshot's schema.
 */
class BackOfficeContractIT extends BookingFixtures {
    JsonNode registrants(String classId, String account) throws Exception {
        return call(GET, "/class-sessions/s08-" + classId + "/bookings", null, as(account), 200).path("items");
    }
    static JsonNode row(JsonNode items, String key, String value) {
        for (var item : items) { if (item.path(key).asText().equals(value)) { return item; } }
        throw new AssertionError("no row with " + key + " = " + value + " in " + items);
    }
    void noShow(JsonNode booking) {
        var stored = booking(booking.path("id").asText());
        mongo.save(new Document("_id", "s08-attendance-" + booking.path("id").asText()).append("clubId", CLUB).append("bookingId", stored.getString("_id"))
                .append("classSessionId", stored.getString("classSessionId")).append("classDate", "2026-10-07").append("classStartsAt", stored.getDate("classStartsAt"))
                .append("classEndsAt", stored.getDate("classEndsAt")).append("dogId", stored.getString("dogId")).append("memberId", stored.getString("memberId"))
                .append("state", "NO_SHOW").append("markedAt", Date.from(clock.instant())).append("markedBy", new Document("accountId", "s08-inst").append("role", "INSTRUCTOR")
                        .append("displayName", "Estela")).append("history", List.of()).append("version", 0L), "attendances");
    }

    /**
     * Step 1 (S08 §6 `displayState`, R-10-16): each registrant row of D4/D12 and the drawer carries the derived label of
     * `GET /bookings/{id}` — a past ACTIVE booking reads DONE, one with a NO_SHOW mark NO_SHOW — and the dog's level code,
     * null for a dog without a level and for every dog with `levels.enabled = false`.
     */
    @Test void S08_6_R_10_16_theRegistrantRowsCarryTheirDisplayStateAndTheDogsLevel() throws Exception {
        var duna = book(as("laura"), "wed", "s08-d-duna"); var nit = book(as("pere"), "wed", "s08-d-nit"); var toby = book(as("joan"), "wed", "s08-d-toby");
        cancel(as("joan"), toby.path("id").asText(), 200);
        var before = registrants("wed", "inst");
        assertThat(row(before, "id", duna.path("id").asText()).path("displayState").asText()).isEqualTo("CONFIRMED");
        assertThat(row(before, "id", toby.path("id").asText()).path("displayState").asText()).isEqualTo("CANCELLED");
        assertThat(row(before, "id", duna.path("id").asText()).path("levelCode").asText()).isEqualTo("C");
        // After the class: the ACTIVE booking is «feta», the one marked NO_SHOW «no presentat»; the cancelled one keeps its label.
        clock.setInstant(local("2026-10-07T20:00"));
        noShow(nit);
        var after = registrants("wed", "admin");
        assertThat(row(after, "id", duna.path("id").asText()).path("displayState").asText()).isEqualTo("DONE");
        assertThat(row(after, "id", nit.path("id").asText()).path("displayState").asText()).isEqualTo("NO_SHOW");
        assertThat(row(after, "id", toby.path("id").asText()).path("displayState").asText()).isEqualTo("CANCELLED");
        for (var item : after) { SnapshotSchemas.assertConforms(item, "ClassBookingItem"); }
        // The same label as the detail reads.
        assertThat(call(GET, "/bookings/" + nit.path("id").asText(), null, as("inst"), 200).path("displayState").asText()).isEqualTo("NO_SHOW");
        // A dog without a level: null; `levels.enabled = false`: null for every row.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-d-nit")), new Update().unset("levelId"), "dogs");
        var noLevel = row(registrants("wed", "inst"), "id", nit.path("id").asText());
        assertThat(noLevel.has("levelCode")).isTrue(); assertThat(noLevel.path("levelCode").isNull()).isTrue();
        parameter("levels.enabled", false);
        for (var item : registrants("wed", "inst")) {
            assertThat(item.path("levelCode").isNull()).as(item.toString()).isTrue(); SnapshotSchemas.assertConforms(item, "ClassBookingItem");
        }
    }

    /**
     * Step 2 (S10 R-10-00): each entry of the staff waiting list carries the owner's first name and the dog's handler (null when
     * the member handles the dog), so D12 writes «En espera: {guia} + {gos}»; a member's own reading has the same form.
     */
    @Test void S08_D12_R_10_00_theStaffWaitlistRowsCarryTheGuideAndTheDog() throws Exception {
        book(as("pere"), "last", "s08-d-nit");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-d-rock")), new Update().set("handlerName", "Marc Example"), "dogs");
        var rock = join(as("laura"), "last", "s08-d-rock", 201); var toby = join(as("joan"), "last", "s08-d-toby", 201);
        for (String reader : List.of("inst", "admin")) {
            var items = call(GET, "/class-sessions/s08-last/waitlist-entries", null, as(reader), 200).path("items");
            var handled = row(items, "id", rock.path("id").asText());
            assertThat(handled.path("memberFirstName").asText()).isEqualTo("Laura"); assertThat(handled.path("handlerName").asText()).isEqualTo("Marc Example");
            var own = row(items, "id", toby.path("id").asText());
            assertThat(own.path("memberFirstName").asText()).isEqualTo("Joan");
            assertThat(own.has("handlerName")).isTrue(); assertThat(own.path("handlerName").isNull()).isTrue();
            for (var item : items) { SnapshotSchemas.assertConforms(item, "WaitlistEntry"); }
        }
        var read = call(GET, "/waitlist-entries/" + rock.path("id").asText(), null, as("laura"), 200);
        assertThat(read.path("memberFirstName").asText()).isEqualTo("Laura"); SnapshotSchemas.assertConforms(read, "WaitlistEntry");
    }

    /**
     * Step 4 (D10 «Classes»): each row of `GET /bookings` carries the class's display description (as the detail shows it), its
     * ring and the ring's colour (null for a ring without one); `fields` selects them like any other key.
     */
    @Test void S08_D10_T_08_47_theBookingListRowsCarryTheClassDescriptionAndItsRing() throws Exception {
        var duna = book(as("laura"), "wed", "s08-d-duna");
        var detail = call(GET, "/bookings/" + duna.path("id").asText(), null, as("admin"), 200);
        var item = call(GET, "/bookings?filter=classSessionId:eq:s08-wed", null, as("admin"), 200).at("/items/0");
        assertThat(item.path("classDescription").asText()).isNotBlank().isEqualTo(detail.at("/classSession/description").asText());
        assertThat(item.path("ringId").asText()).isEqualTo("s08-ring"); assertThat(item.path("ringName").asText()).isEqualTo("Central");
        assertThat(item.path("ringColor").asText()).isEqualTo("#8FCE8F");
        SnapshotSchemas.assertConforms(item, "BookingListItem");
        var sparse = call(GET, "/bookings?filter=classSessionId:eq:s08-wed&fields=ringColor,classDescription", null, as("inst"), 200).at("/items/0");
        assertThat(SnapshotSchemas.keys(sparse)).containsExactlyInAnyOrder("id", "ringColor", "classDescription");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-ring")), new Update().unset("color"), "rings");
        var colourless = call(GET, "/bookings?filter=classSessionId:eq:s08-wed", null, as("admin"), 200).at("/items/0");
        assertThat(colourless.has("ringColor")).isTrue(); assertThat(colourless.path("ringColor").isNull()).isTrue();
        SnapshotSchemas.assertConforms(colourless, "BookingListItem");
    }

    /**
     * Step 6 (CONVENCIONS_API §4): `GET /bookings/filter-values` counts each value over the whole filtered set, never over one
     * page: of 1001 bookings, the one dog that only the last row (after the first 1000 in the list's order) has is listed.
     * An undeclared field is 400 INVALID_FILTER; the list has no free-text search, so `q` is refused the same way.
     */
    @Test void CONVENCIONS_API_4_T_08_47_bookingFilterValuesCountTheWholeFilteredSet() throws Exception {
        var rows = new ArrayList<Document>(); var first = local("2026-10-07T18:50");
        for (int i = 0; i <= 1000; i++) {
            boolean last = i == 1000;
            rows.add(new Document("_id", "s08-fv-" + i).append("clubId", CLUB).append("classSessionId", "s08-wed").append("dogId", last ? "s08-d-nit" : "s08-d-duna")
                    .append("memberId", last ? "s08-m-pere" : "s08-m-laura").append("state", "ACTIVE").append("origin", "APP").append("bookingWeekKey", "2026-10-04")
                    .append("classStartsAt", Date.from(first.plusSeconds(60L * i))).append("bookedAt", Date.from(NOW)));
        }
        mongo.getCollection("bookings").insertMany(rows);
        var dogs = call(GET, "/bookings/filter-values?field=dogId&filter=state:eq:ACTIVE", null, as("admin"), 200);
        assertThat(dogs.path("field").asText()).isEqualTo("dogId");
        assertThat(dogs.path("values")).extracting(v -> v.path("value").asText() + ":" + v.path("label").asText() + ":" + v.path("count").asLong())
                .containsExactly("s08-d-duna:Duna:1000", "s08-d-nit:Nit:1");
        SnapshotSchemas.assertConforms(dogs, "FilterValues");
        var members = call(GET, "/bookings/filter-values?field=memberId&filter=dogId:eq:s08-d-nit", null, as("inst"), 200).path("values");
        assertThat(members).extracting(v -> v.path("label").asText() + ":" + v.path("count").asLong()).containsExactly("Pere Example:1");
        assertThat(code(call(GET, "/bookings/filter-values?field=dogName", null, as("admin"), 400))).isEqualTo("INVALID_FILTER");
        assertThat(code(call(GET, "/bookings/filter-values?field=late", null, as("admin"), 400))).isEqualTo("INVALID_FILTER");
        assertThat(code(call(GET, "/bookings/filter-values?field=dogId&q=Duna", null, as("admin"), 400))).isEqualTo("INVALID_FILTER");
        assertThat(code(call(GET, "/bookings?q=Duna", null, as("admin"), 400))).isEqualTo("INVALID_FILTER");
        call(GET, "/bookings/filter-values?field=dogId", null, as("laura"), 403);
        assertThat(code(call(GET, "/bookings/filter-values?field=dogId", null, impersonating("admin", "s08-m-laura"), 403))).isEqualTo("IMPERSONATION_DENIED");
    }

    /**
     * Step 7 (S08 §6, §2 row 29 amended 26-09): `BOOKING_LIMIT_REACHED.details.nextBookableAt` is the start of the next booking
     * week, `week(now).end()`, for a NEXT class as for a CURRENT one: Sunday 11-10 20:00 Madrid (18:00Z), never the end of the
     * class's own week. The seat hold, the confirmation and the waiting list give the same instant.
     */
    @Test void S08_29_T_08_14_aNextClassRefusedAtTheLimitCarriesTheComingOpening() throws Exception {
        // bookings.maxNextWeek = 0: a NEXT class is refused at the hold.
        parameter("bookings.maxNextWeek", 0);
        var hold = hold(as("laura"), "mon", "s08-d-duna", 409);
        assertThat(code(hold)).isEqualTo("BOOKING_LIMIT_REACHED"); assertThat(hold.at("/details/week").asText()).isEqualTo("NEXT");
        assertThat(hold.at("/details/nextBookableAt").asText()).isEqualTo("2026-10-11T18:00:00Z");
        // The waiting list of a full NEXT class.
        parameter("bookings.maxNextWeek", 1);
        for (int i = 0; i < 3; i++) { book(as("c" + i), "mon", "s08-d-c" + i); }
        parameter("bookings.maxNextWeek", 0);
        var join = join(as("laura"), "mon", "s08-d-duna", 409);
        assertThat(code(join)).isEqualTo("BOOKING_LIMIT_REACHED"); assertThat(join.at("/details/nextBookableAt").asText()).isEqualTo("2026-10-11T18:00:00Z");
        // The confirmation: the limit changed between the hold and the POST.
        parameter("bookings.maxNextWeek", 1);
        var held = hold(as("laura"), "mon2", "s08-d-duna", 201);
        parameter("bookings.maxNextWeek", 0);
        var confirm = confirm(as("laura"), held.path("id").asText(), null, 409);
        assertThat(code(confirm)).isEqualTo("BOOKING_LIMIT_REACHED"); assertThat(confirm.at("/details/nextBookableAt").asText()).isEqualTo("2026-10-11T18:00:00Z");
    }

    /**
     * Step 7, the limit reached by a NEXT booking that cannot be swapped: on Sunday 11-10 at 19:00 Madrid the 21:00 class
     * already belongs to the next week and is inside the 4 h threshold, so a second NEXT class is refused. The coming opening
     * is one hour ahead, at 20:00, not a week later.
     */
    @Test void S08_29_T_08_14_aNextClassRefusedWithoutASwapCarriesTheComingOpening() throws Exception {
        session("sun21", "2026-10-11T21:00", 3, List.of());
        clock.setInstant(local("2026-10-11T19:00"));
        book(as("laura"), "sun21", "s08-d-duna");
        var refused = hold(as("laura"), "mon", "s08-d-duna", 409);
        assertThat(code(refused)).isEqualTo("BOOKING_LIMIT_REACHED"); assertThat(refused.at("/details/week").asText()).isEqualTo("NEXT");
        assertThat(refused.at("/details/swappable")).isEmpty();
        assertThat(refused.at("/details/notSelectable")).extracting(n -> n.path("reason").asText()).containsExactly("LATE_WINDOW");
        assertThat(refused.at("/details/nextBookableAt").asText()).isEqualTo("2026-10-11T18:00:00Z");
    }

    /**
     * Step 9 (E5-W04, mockup 07's dot): every `Booking` a MEMBER reads carries the class's ring colour in `classSession.ringColor`
     * — the confirmation, the detail, `/me/bookings` and the cancellation — and so does a waiting-list entry's class card.
     */
    @Test void S08_07_T_08_15_everyBookingAMemberReadsCarriesTheRingColour() throws Exception {
        var booked = book(as("laura"), "wed", "s08-d-duna");
        assertThat(booked.at("/classSession/ringColor").asText()).isEqualTo("#8FCE8F"); SnapshotSchemas.assertConforms(booked, "Booking");
        for (String reader : List.of("laura", "joan", "inst")) {
            assertThat(call(GET, "/bookings/" + booked.path("id").asText(), null, as(reader), 200).at("/classSession/ringColor").asText()).isEqualTo("#8FCE8F");
        }
        assertThat(call(GET, "/me/bookings", null, as("laura"), 200).at("/items/0/classSession/ringColor").asText()).isEqualTo("#8FCE8F");
        var cancelled = cancel(as("laura"), booked.path("id").asText(), 200);
        assertThat(cancelled.at("/classSession/ringColor").asText()).isEqualTo("#8FCE8F"); SnapshotSchemas.assertConforms(cancelled, "Booking");
        book(as("pere"), "last", "s08-d-nit");
        var entry = join(as("laura"), "last", "s08-d-duna", 201);
        assertThat(entry.at("/classSession/ringColor").asText()).isEqualTo("#8FCE8F");
        // A ring without a colour: null, as the schema allows.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-ring")), new Update().unset("color"), "rings");
        var read = call(GET, "/bookings/" + booked.path("id").asText(), null, as("laura"), 200);
        assertThat(read.at("/classSession").has("ringColor")).isTrue(); assertThat(read.at("/classSession/ringColor").isNull()).isTrue();
        SnapshotSchemas.assertConforms(read, "Booking");
    }

    /**
     * Round 2 (ruling E75, CONVENCIONS_API §4): the lists without free-text search no longer declare `q` (`E5BackOfficeContractTest`)
     * and keep answering a non-blank one with 400 INVALID_FILTER, never a 500; a blank `q` is no search.
     */
    @Test void CONVENCIONS_API_4_theListsWithoutSearchRefuseANonBlankQ() throws Exception {
        for (String path : List.of("/bookings", "/bookings/filter-values?field=state", "/class-sessions", "/weeks", "/attendances", "/jobs/cleanup/runs",
                "/notifications", "/notifications/filter-values?field=code", "/notifications/export?format=xlsx")) {
            String separator = path.contains("?") ? "&" : "?";
            assertThat(code(call(GET, path + separator + "q=Duna", null, as("admin"), 400))).as(path).isEqualTo("INVALID_FILTER");
            call(GET, path + separator + "q=", null, as("admin"), 200);
        }
    }
}
