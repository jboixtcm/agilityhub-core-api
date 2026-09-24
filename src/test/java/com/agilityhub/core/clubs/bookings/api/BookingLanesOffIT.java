package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.shared.application.LocalLanes;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.support.ConcurrencySupport;
import com.agilityhub.core.support.ConcurrencySupport.HeldTransaction;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * E5-T07 · S08 R-08-07 on the Mongo path alone: the local lanes are off, so concurrent writers of one class meet only
 * the `seat_locks` `$inc` write conflict and its retries (T-08-29/31/32).
 */
@TestPropertySource(properties = "core.concurrency.local-lanes=false")
class BookingLanesOffIT extends BookingFixtures {
    static final String CONTEXT = "bookings";
    @Autowired LocalLanes lanes; @Autowired TransactionRetries retries; @Autowired SeatLockRepository seatLocks;
    record Reply(int status, JsonNode body) { String code() { return body.path("code").asText(); } String outcome() { return status + (status >= 400 ? " " + code() : ""); } }
    Reply send(String path, Object body, RequestPostProcessor auth, String key) throws Exception {
        var request = post("/api/v1" + path).header("Host", HOST).with(auth).contentType("application/json").content(mapper.writeValueAsBytes(body));
        if (key != null) { request.header("Idempotency-Key", key); }
        var response = mvc.perform(request).andReturn().getResponse();
        return new Reply(response.getStatus(), response.getContentAsByteArray().length == 0 ? mapper.nullNode() : mapper.readTree(response.getContentAsString()));
    }
    static Map<String, Long> tally(List<Reply> replies) {
        var result = new TreeMap<String, Long>(); replies.forEach(r -> result.merge(r.outcome(), 1L, Long::sum)); return result;
    }
    long seatLock(String classId) { var lock = mongo.findById(classId, Document.class, "seat_locks"); return lock == null ? 0 : ((Number) lock.get("version")).longValue(); }

    @Test void T_08_29_lanesOffTheLastSeatBurstNeverOverbooksAndEveryRequestEndsHeldFullOrStale() throws Exception {
        assertThat(lanes.enabled()).isFalse();
        double retried = retries.retries(CONTEXT), exhausted = retries.exhaustions(CONTEXT);
        var holds = ConcurrencySupport.parallel(20, i -> () -> send("/seat-holds", Map.of("classSessionId", "s08-last", "dogId", "s08-d-c" + i), as("c" + i), null));
        var counts = tally(holds);
        double retriedNow = retries.retries(CONTEXT) - retried, exhaustedNow = retries.exhaustions(CONTEXT) - exhausted;
        System.out.println("E5-T07 T-08-29 lanes off, 20 holds for the last seat: " + counts + " · retries " + retriedNow + " · exhausted " + exhaustedNow);
        assertThat(counts.keySet()).isSubsetOf("201", "409 CLASS_FULL", "409 STALE_VERSION");
        assertThat(counts).containsEntry("201", 1L);
        assertThat(retriedNow).as("the seat_locks write conflict was met and retried").isPositive();
        assertThat((long) exhaustedNow).isEqualTo(counts.getOrDefault("409 STALE_VERSION", 0L));
        assertThat(count("seat_holds", Criteria.where("classSessionId").is("s08-last"))).isEqualTo(1);
        // Everybody now confirms at once: the holder wins, the others meet CLASS_FULL, a foreign hold or STALE_VERSION.
        var winner = holds.stream().filter(r -> r.status() == 201).findFirst().orElseThrow();
        int index = Integer.parseInt(winner.body().path("dogId").asText().substring("s08-d-c".length()));
        var bookings = ConcurrencySupport.parallel(20, i -> () -> i == index
                ? send("/bookings", Map.of("seatHoldId", winner.body().path("id").asText()), as("c" + i), UUID.randomUUID().toString())
                : send("/seat-holds", Map.of("classSessionId", "s08-last", "dogId", "s08-d-c" + i), as("c" + i), null));
        System.out.println("E5-T07 T-08-29 lanes off, the holder confirms while 19 hold again: " + tally(bookings));
        assertThat(bookings.get(index).status()).isIn(201, 409);
        assertThat(bookings).allSatisfy(r -> assertThat(r.outcome()).isIn("201", "409 CLASS_FULL", "409 STALE_VERSION", "409 SEAT_HOLD_EXPIRED"));
        long booked = count("bookings", Criteria.where("classSessionId").is("s08-last").and("state").in("ACTIVE", "PAYMENT_PENDING"));
        assertThat(booked).as("never an overbooking").isLessThanOrEqualTo(1);
        assertThat(session("last").get("counters", Document.class)).containsEntry("booked", (int) booked);
    }

    @Test void T_08_29_aHoldThatMeetsAHeldSeatLockIsRetriedAndThenAnswered() throws Exception {
        long version = seatLock("s08-last"); double before = retries.retries(CONTEXT, "write_conflict");
        try (var held = new HeldTransaction(tx, CLUB, () -> seatLocks.lock("s08-last"))) {
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                double base = retries.retries(CONTEXT);
                var request = pool.submit(() -> send("/seat-holds", Map.of("classSessionId", "s08-last", "dogId", "s08-d-nit"), as("pere"), null));
                ConcurrencySupport.awaitRetry(retries, CONTEXT, base);
                held.commit();
                assertThat(request.get().outcome()).isEqualTo("201");
            } finally { pool.shutdownNow(); }
        }
        assertThat(retries.retries(CONTEXT, "write_conflict")).isGreaterThan(before);
        assertThat(seatLock("s08-last") - version).as("the held $inc and the retried hold").isEqualTo(2);
    }

    @Test void T_08_31_lanesOffTwoKeysForOneHoldGiveOneBooking() throws Exception {
        var held = hold(as("pere"), "wed", "s08-d-nit", 201);
        var replies = ConcurrencySupport.parallel(2, i -> () -> send("/bookings", Map.of("seatHoldId", held.path("id").asText()), as("pere"), UUID.randomUUID().toString()));
        System.out.println("E5-T07 T-08-31 lanes off, two keys for one hold: " + tally(replies));
        assertThat(replies).allSatisfy(r -> assertThat(r.outcome()).isIn("201", "409 SEAT_HOLD_EXPIRED", "409 STALE_VERSION"));
        assertThat(replies.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(count("bookings", Criteria.where("dogId").is("s08-d-nit"))).isEqualTo(1);
        assertThat(session("wed").get("counters", Document.class)).containsEntry("booked", 1);
    }

    @Test void T_08_32_lanesOffASwapRacingACancellationOfTheSameOldBookingLeavesCoherentCounters() throws Exception {
        for (int round = 0; round < 3; round++) {
            fixtures();
            var old = book(as("laura"), "mon", "s08-d-duna");
            var held = hold(as("laura"), "mon2", "s08-d-duna", 201);
            var replies = ConcurrencySupport.parallel(2, i -> () -> i == 0
                    ? send("/bookings", Map.of("seatHoldId", held.path("id").asText(), "swapBookingId", old.path("id").asText()), as("laura"), UUID.randomUUID().toString())
                    : send("/bookings/" + old.path("id").asText() + "/cancellation", Map.of(), as("laura"), null));
            System.out.println("E5-T07 T-08-32 lanes off, round " + round + " swap vs cancellation: " + replies.stream().map(Reply::outcome).toList());
            var swap = replies.get(0); var cancellation = replies.get(1);
            assertThat(swap.outcome()).isIn("201", "422 SWAP_NOT_ALLOWED", "409 STALE_VERSION");
            assertThat(cancellation.outcome()).isIn("200", "422 BOOKING_NOT_CANCELLABLE", "409 STALE_VERSION");
            assertThat(swap.status() == 201 && cancellation.status() == 200).as("never both").isFalse();
            long oldLive = count("bookings", Criteria.where("_id").is(old.path("id").asText()).and("state").in("ACTIVE", "PAYMENT_PENDING"));
            long newLive = count("bookings", Criteria.where("classSessionId").is("s08-mon2").and("state").in("ACTIVE", "PAYMENT_PENDING"));
            assertThat(oldLive + newLive).as("the dog holds at most one of the two seats").isLessThanOrEqualTo(1);
            assertThat(session("mon").get("counters", Document.class)).containsEntry("booked", (int) oldLive);
            assertThat(session("mon2").get("counters", Document.class)).containsEntry("booked", (int) newLive);
        }
    }
}
