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
 * E5-T25 over real Mongo: the data the S08 member flow (screens 06/29, 07 and the waiting-list detail) needs from the
 * api, found by the web's E5-W01 — the booked and the waiting dog, the R-08-10 in-time deadline, `BookedBy.self` and
 * one `Idempotency-Key` per body (R-08-08). Every answer is checked against the committed snapshot's schema. 03's rows
 * are in {@link MemberAggregatesIT}.
 */
class MemberFlowContractIT extends BookingFixtures {
    JsonNode detail(String account, String bookingId) throws Exception { return call(GET, "/bookings/" + bookingId, null, as(account), 200); }
    static void dog(JsonNode dog, String id, String name, String sex) {
        assertThat(dog.path("id").asText()).isEqualTo(id); assertThat(dog.path("name").asText()).isEqualTo(name); assertThat(dog.path("sex").asText()).isEqualTo(sex);
    }

    /** Step 1: `Booking.dog` on the POST answer, the detail (own, family group, staff), `/me/bookings` and the cancellation answer. */
    @Test void S08_07_T_08_15_everyBookingCarriesItsDog() throws Exception {
        var duna = book(as("laura"), "wed", "s08-d-duna");
        dog(duna.path("dog"), "s08-d-duna", "Duna", "FEMALE"); SnapshotSchemas.assertConforms(duna, "Booking");
        var rock = book(as("laura"), "levelD", "s08-d-rock");
        dog(rock.path("dog"), "s08-d-rock", "Rock", "MALE");
        for (String reader : List.of("laura", "joan", "inst", "admin")) {
            var read = detail(reader, duna.path("id").asText());
            dog(read.path("dog"), "s08-d-duna", "Duna", "FEMALE"); SnapshotSchemas.assertConforms(read, "Booking");
        }
        var mine = call(GET, "/me/bookings", null, as("joan"), 200).path("items");
        assertThat(mine).extracting(b -> b.at("/dog/name").asText() + ":" + b.at("/dog/sex").asText()).containsExactlyInAnyOrder("Duna:FEMALE", "Rock:MALE");
        var cancelled = cancel(as("laura"), rock.path("id").asText(), 200);
        dog(cancelled.path("dog"), "s08-d-rock", "Rock", "MALE"); SnapshotSchemas.assertConforms(cancelled, "Booking");
    }

    /**
     * Step 2 (R-08-10): the club's threshold and the api's deadline on every booking a member reads, the cancellation
     * answer included; the deadline is the last in-time instant of the cancellation rule itself.
     */
    @Test void R_08_10_T_08_06_everyBookingCarriesTheThresholdAndTheInTimeDeadline() throws Exception {
        var wed = book(as("laura"), "wed", "s08-d-duna"); var thu = book(as("laura"), "thu", "s08-d-duna");
        // The product default (the Cànic's value): 240 min before Wednesday 18:50 Madrid (16:50Z).
        assertThat(wed.path("lateCancelThresholdMinutes").asInt()).isEqualTo(240);
        assertThat(wed.path("cancellableInTimeUntil").asText()).isEqualTo("2026-10-07T12:50:00Z");
        // A club value: every read follows it (a MEMBER never reads /parameters).
        parameter("bookings.lateCancelThresholdMinutes", 90);
        var read = detail("laura", wed.path("id").asText());
        assertThat(read.path("lateCancelThresholdMinutes").asInt()).isEqualTo(90);
        assertThat(read.path("cancellableInTimeUntil").asText()).isEqualTo("2026-10-07T15:20:00Z");
        var mine = call(GET, "/me/bookings", null, as("laura"), 200).path("items");
        assertThat(mine).extracting(b -> b.path("cancellableInTimeUntil").asText()).containsExactlyInAnyOrder("2026-10-07T15:20:00Z", "2026-10-08T15:20:00Z");
        // At the deadline itself a cancellation is in time; one second later it is late. Both answers name the threshold.
        clock.setInstant(Instant.parse(read.path("cancellableInTimeUntil").asText()));
        var inTime = cancel(as("laura"), wed.path("id").asText(), 200);
        assertThat(inTime.at("/cancellation/late").asBoolean()).isFalse(); assertThat(inTime.path("lateCancelThresholdMinutes").asInt()).isEqualTo(90);
        SnapshotSchemas.assertConforms(inTime, "Booking");
        clock.setInstant(Instant.parse(detail("laura", thu.path("id").asText()).path("cancellableInTimeUntil").asText()).plusSeconds(1));
        var late = cancel(as("laura"), thu.path("id").asText(), 200);
        assertThat(late.at("/cancellation/late").asBoolean()).isTrue(); assertThat(late.path("state").asText()).isEqualTo("CANCELLED_LATE");
        assertThat(late.path("lateCancelThresholdMinutes").asInt()).isEqualTo(90); assertThat(late.path("cancellableInTimeUntil").asText()).isEqualTo("2026-10-08T15:20:00Z");
    }

    /**
     * Step 2, T-08-42: the threshold is elapsed time. Sunday 25-10-2026 09:00 Madrid is 08:00Z, after the 03:00 → 02:00
     * change; 8 h before it is 00:00Z (02:00 summer time), not 01:00 local as a wall-clock subtraction would say.
     */
    @Test void T_08_42_theInTimeDeadlineIsElapsedTimeAcrossTheDaylightSavingChange() throws Exception {
        session("dst", "2026-10-25T09:00", 3, List.of());
        clock.setInstant(local("2026-10-24T10:00"));
        parameter("bookings.lateCancelThresholdMinutes", 480);
        var booking = book(as("laura"), "dst", "s08-d-duna");
        assertThat(booking.path("cancellableInTimeUntil").asText()).isEqualTo("2026-10-25T00:00:00Z");
        assertThat(Instant.parse(booking.path("cancellableInTimeUntil").asText())).isNotEqualTo(local("2026-10-25T01:00"));
        clock.setInstant(Instant.parse("2026-10-25T00:00:01Z"));
        assertThat(cancel(as("laura"), booking.path("id").asText(), 200).at("/cancellation/late").asBoolean()).isTrue();
    }

    /** Step 3: `WaitlistEntry.dog` on the join answer, the detail (own and family group) and the leave answer. */
    @Test void S08_07_T_08_19_everyWaitlistEntryCarriesItsDog() throws Exception {
        book(as("pere"), "last", "s08-d-nit");
        var joined = join(as("laura"), "last", "s08-d-rock", 201);
        dog(joined.path("dog"), "s08-d-rock", "Rock", "MALE"); assertThat(joined.path("dogName").asText()).isEqualTo("Rock");
        SnapshotSchemas.assertConforms(joined, "WaitlistEntry");
        for (String reader : List.of("laura", "joan", "inst")) {
            var read = call(GET, "/waitlist-entries/" + joined.path("id").asText(), null, as(reader), 200);
            dog(read.path("dog"), "s08-d-rock", "Rock", "MALE"); SnapshotSchemas.assertConforms(read, "WaitlistEntry");
        }
        var left = call(POST, "/waitlist-entries/" + joined.path("id").asText() + "/cancellation", null, as("laura"), 200);
        dog(left.path("dog"), "s08-d-rock", "Rock", "MALE"); SnapshotSchemas.assertConforms(left, "WaitlistEntry");
    }

    /**
     * Step 5: `BookedBy.self` compares the reader's account with the booker's, never names: another group member with
     * the same first name is not «self», and an impersonated booking is the club's.
     */
    @Test void S08_07_T_08_27_bookedBySelfComparesAccountsNotNames() throws Exception {
        var own = book(as("laura"), "wed", "s08-d-duna");
        var by = own.path("bookedBy");
        assertThat(by.path("self").asBoolean()).isTrue(); assertThat(by.path("viaClub").asBoolean()).isFalse(); assertThat(by.path("displayName").asText()).isEqualTo("Laura");
        assertThat(detail("laura", own.path("id").asText()).at("/bookedBy/self").asBoolean()).isTrue();
        assertThat(detail("joan", own.path("id").asText()).at("/bookedBy/self").asBoolean()).isFalse();
        assertThat(detail("inst", own.path("id").asText()).at("/bookedBy/self").asBoolean()).isFalse();
        // Joan, renamed «Laura», books Laura's Duna: same display name, another account.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-joan")), new Update().set("firstName", "Laura"), "members");
        var byJoan = book(as("joan"), "thu", "s08-d-duna");
        assertThat(byJoan.at("/bookedBy/self").asBoolean()).isTrue();
        var readByLaura = detail("laura", byJoan.path("id").asText());
        assertThat(readByLaura.at("/bookedBy/displayName").asText()).isEqualTo("Laura");
        assertThat(readByLaura.at("/bookedBy/self").asBoolean()).isFalse(); assertThat(readByLaura.at("/bookedBy/viaClub").asBoolean()).isFalse();
        // The admin acting as Laura: the booking is the club's for Laura and for the impersonation token alike.
        var imp = impersonating("admin", "s08-m-laura");
        var byClub = book(imp, "fri", "s08-d-rock");
        assertThat(byClub.at("/bookedBy/self").asBoolean()).isFalse(); assertThat(byClub.at("/bookedBy/viaClub").asBoolean()).isTrue();
        assertThat(detail("laura", byClub.path("id").asText()).at("/bookedBy/self").asBoolean()).isFalse();
        // The same token reading Laura's own booking reads it as Laura's view: «self».
        assertThat(call(GET, "/bookings/" + own.path("id").asText(), null, imp, 200).at("/bookedBy/self").asBoolean()).isTrue();
        // The cancellation answer keeps it.
        var cancelled = cancel(as("laura"), own.path("id").asText(), 200);
        assertThat(cancelled.at("/bookedBy/self").asBoolean()).isTrue(); SnapshotSchemas.assertConforms(cancelled.path("bookedBy"), "BookedBy");
    }

    /**
     * Step 6 (R-08-08, amended 26-09): one key per body. A failed swap choice is replayed on its key; another choice for the
     * same hold with a new key is processed; the first key with the new body is refused.
     */
    @Test void R_08_08_T_08_16_eachBodyTakesItsOwnIdempotencyKey() throws Exception {
        var mon = book(as("laura"), "mon", "s08-d-duna"); var wed = book(as("laura"), "wed", "s08-d-duna");
        var held = hold(as("laura"), "mon2", "s08-d-duna", 201);
        assertThat(held.at("/limit/swappable")).extracting(s -> s.path("bookingId").asText()).containsExactly(mon.path("id").asText());
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        var wrongChoice = Map.of("seatHoldId", held.path("id").asText(), "swapBookingId", wed.path("id").asText());
        var rightChoice = Map.of("seatHoldId", held.path("id").asText(), "swapBookingId", mon.path("id").asText());
        var refused = call(POST, "/bookings", wrongChoice, as("laura"), 422, first);
        assertThat(code(refused)).isEqualTo("SWAP_NOT_ALLOWED");
        // The same key with the same body: the stored answer, nothing re-executes.
        assertThat(call(POST, "/bookings", wrongChoice, as("laura"), 422, first)).isEqualTo(refused);
        // The same key with another body: refused, nothing changes.
        var reused = call(POST, "/bookings", rightChoice, as("laura"), 409, first);
        assertThat(code(reused)).isEqualTo("IDEMPOTENCY_KEY_REUSED"); assertThat(reused.at("/details/reason").asText()).isEqualTo("DIFFERENT_REQUEST");
        assertThat(booking(mon.path("id").asText()).getString("state")).isEqualTo("ACTIVE");
        assertThat(count("bookings", Criteria.where("classSessionId").is("s08-mon2"))).isZero();
        // A new key with the new body on the same hold: processed.
        var swapped = call(POST, "/bookings", rightChoice, as("laura"), 201, second);
        assertThat(swapped.path("swapFromBookingId").asText()).isEqualTo(mon.path("id").asText());
        assertThat(booking(mon.path("id").asText())).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "SWAP");
        // Its retry replays the 201.
        assertThat(call(POST, "/bookings", rightChoice, as("laura"), 201, second)).isEqualTo(swapped);
        assertThat(count("bookings", Criteria.where("classSessionId").is("s08-mon2"))).isEqualTo(1);
        // A third body (no swap) on the consumed hold: 409 SEAT_HOLD_EXPIRED, and its retry replays the stored 409.
        String third = UUID.randomUUID().toString(); var noSwap = Map.of("seatHoldId", held.path("id").asText());
        var expired = call(POST, "/bookings", noSwap, as("laura"), 409, third);
        assertThat(code(expired)).isEqualTo("SEAT_HOLD_EXPIRED");
        assertThat(call(POST, "/bookings", noSwap, as("laura"), 409, third)).isEqualTo(expired);
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("key").in(first, second, third)), Document.class, "idempotency_records")).isEqualTo(3);
    }
}
