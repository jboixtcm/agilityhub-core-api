package com.agilityhub.core.clubs.bookings.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** S08 R-08-07/08/09 under concurrency: parallel executors against the real Mongo replica set. */
class BookingConcurrencyIT extends BookingFixtures {
    record Reply(int status, JsonNode body) { String code() { return body.path("code").asText(); } }
    Reply send(String path, Object body, RequestPostProcessor auth, String key) throws Exception {
        var request = post("/api/v1" + path).header("Host", HOST).with(auth).contentType("application/json").content(mapper.writeValueAsBytes(body));
        if (key != null) { request.header("Idempotency-Key", key); }
        var response = mvc.perform(request).andReturn().getResponse();
        return new Reply(response.getStatus(), response.getContentAsByteArray().length == 0 ? mapper.nullNode() : mapper.readTree(response.getContentAsString()));
    }
    <T> List<T> parallel(int threads, java.util.function.IntFunction<Callable<T>> work) throws Exception {
        var pool = Executors.newFixedThreadPool(threads); var start = new CountDownLatch(1); var futures = new ArrayList<Future<T>>();
        try {
            for (int i = 0; i < threads; i++) { var task = work.apply(i); futures.add(pool.submit(() -> { start.await(); return task.call(); })); }
            start.countDown(); var results = new ArrayList<T>();
            for (var future : futures) { results.add(future.get(120, TimeUnit.SECONDS)); }
            return results;
        } finally { pool.shutdownNow(); }
    }
    Map<String, Long> tally(List<Reply> replies) {
        var result = new TreeMap<String, Long>();
        replies.forEach(r -> result.merge(r.status() + (r.status() >= 400 ? " " + r.code() : ""), 1L, Long::sum)); return result;
    }

    @Test void T_08_29_twentyParallelHoldsForTheLastSeatGiveOneHoldAndNoOverbooking() throws Exception {
        var replies = parallel(20, i -> () -> send("/seat-holds", Map.of("classSessionId", "s08-last", "dogId", "s08-d-c" + i), as("c" + i), null));
        var counts = tally(replies);
        System.out.println("T-08-29 holds for the last seat: " + counts);
        assertThat(counts).containsExactly(Map.entry("201", 1L), Map.entry("409 CLASS_FULL", 19L));
        assertThat(replies.stream().filter(r -> r.status() == 409).allMatch(r -> r.body().at("/details/heldOnly").asBoolean())).isTrue();
        var winner = replies.stream().filter(r -> r.status() == 201).findFirst().orElseThrow();
        int index = Integer.parseInt(winner.body().path("dogId").asText().substring("s08-d-c".length()));
        var confirmations = parallel(20, i -> () -> send("/bookings", Map.of("seatHoldId", winner.body().path("id").asText()), as("c" + (i == 0 ? index : i)), UUID.randomUUID().toString()));
        System.out.println("T-08-29 confirmations of the winning hold: " + tally(confirmations));
        assertThat(count("bookings", Criteria.where("classSessionId").is("s08-last"))).isEqualTo(1);
        assertThat(session("last").get("counters", Document.class)).containsEntry("booked", 1);
        var after = parallel(20, i -> () -> send("/seat-holds", Map.of("classSessionId", "s08-last", "dogId", "s08-d-c" + i), as("c" + i), null));
        System.out.println("T-08-29 holds after the booking: " + tally(after));
        assertThat(after).allSatisfy(r -> assertThat(r.status()).isIn(409));
    }

    @Test void T_08_31_aDoubleClickBooksOnceAndTwoKeysForOneHoldGiveOneBookingAndSeatHoldExpired() throws Exception {
        var held = hold(as("laura"), "wed", "s08-d-duna", 201); String key = UUID.randomUUID().toString();
        var same = parallel(2, i -> () -> send("/bookings", Map.of("seatHoldId", held.path("id").asText()), as("laura"), key));
        var sameCounts = tally(same);
        System.out.println("T-08-31 same key: " + sameCounts);
        assertThat(count("bookings", Criteria.where("classSessionId").is("s08-wed"))).isEqualTo(1);
        assertThat(same).allSatisfy(r -> assertThat(r.status() == 201 || r.status() == 409 && r.code().equals("IDEMPOTENCY_KEY_REUSED")).isTrue());
        var ids = same.stream().filter(r -> r.status() == 201).map(r -> r.body().path("id").asText()).distinct().toList();
        assertThat(ids).hasSize(1);
        var other = hold(as("pere"), "wed", "s08-d-nit", 201);
        var keys = parallel(2, i -> () -> send("/bookings", Map.of("seatHoldId", other.path("id").asText()), as("pere"), UUID.randomUUID().toString()));
        var keysCounts = tally(keys);
        System.out.println("T-08-31 two keys, one hold: " + keysCounts);
        assertThat(keysCounts).containsExactly(Map.entry("201", 1L), Map.entry("409 SEAT_HOLD_EXPIRED", 1L));
        assertThat(count("bookings", Criteria.where("dogId").is("s08-d-nit"))).isEqualTo(1);
    }

    @Test void T_08_32_aSwapRacingACancellationOfTheSameOldBookingLeavesCoherentCounters() throws Exception {
        for (int round = 0; round < 3; round++) {
            fixtures();
            var old = book(as("laura"), "mon", "s08-d-duna");
            var held = hold(as("laura"), "mon2", "s08-d-duna", 201);
            var replies = parallel(2, i -> () -> i == 0
                    ? send("/bookings", Map.of("seatHoldId", held.path("id").asText(), "swapBookingId", old.path("id").asText()), as("laura"), UUID.randomUUID().toString())
                    : send("/bookings/" + old.path("id").asText() + "/cancellation", Map.of(), as("laura"), null));
            System.out.println("T-08-32 round " + round + " swap vs cancellation: " + replies.stream().map(r -> r.status() + (r.status() >= 400 ? " " + r.code() : "")).toList());
            var swap = replies.get(0); var cancellation = replies.get(1);
            assertThat(swap.status() == 201 ^ cancellation.status() == 200).as("exactly one wins").isTrue();
            if (swap.status() != 201) { assertThat(swap.code()).isIn("SWAP_NOT_ALLOWED"); }
            if (cancellation.status() != 200) { assertThat(cancellation.code()).isEqualTo("BOOKING_NOT_CANCELLABLE"); }
            assertThat(booking(old.path("id").asText()).getString("state")).isEqualTo("CANCELLED");
            long live = count("bookings", Criteria.where("dogId").is("s08-d-duna").and("state").in("ACTIVE", "PAYMENT_PENDING"));
            assertThat(live).isEqualTo(swap.status() == 201 ? 1 : 0);
            assertThat(session("mon").get("counters", Document.class)).containsEntry("booked", 0);
            assertThat(session("mon2").get("counters", Document.class)).containsEntry("booked", (int) live);
        }
    }

    @Test void T_08_30_tenNotifiedEntriesClaimInParallelOnlyTheFreeSeatsAreTakenAndTheRestGoBackToActive() throws Exception {
        for (int seats : List.of(1, 2)) {
            fixtures(); parameter("waitlist.maxPerClass", 10);
            session("claim", "2026-10-08T12:00", seats, List.of());
            var booked = new ArrayList<String>();
            for (String holder : List.of("pere:s08-d-nit", "joan:s08-d-toby").subList(0, seats)) {
                booked.add(book(as(holder.split(":")[0]), "claim", holder.split(":")[1]).path("id").asText());
            }
            var entries = new ArrayList<String>();
            for (int i = 0; i < 10; i++) { entries.add(join(as("c" + i), "claim", "s08-d-c" + i, 201).path("id").asText()); }
            for (int i = 0; i < seats; i++) { cancel(as(i == 0 ? "pere" : "joan"), booked.get(i), 200); }
            dispatch();
            assertThat(count("waitlist_entries", Criteria.where("classSessionId").is("s08-claim").and("state").is("NOTIFIED"))).isEqualTo(10);
            var replies = parallel(10, i -> () -> {
                var held = send("/seat-holds", Map.of("classSessionId", "s08-claim", "dogId", "s08-d-c" + i, "waitlistEntryId", entries.get(i)), as("c" + i), null);
                if (held.status() != 201) { return held; }
                return send("/waitlist-entries/" + entries.get(i) + "/claim", Map.of("seatHoldId", held.body().path("id").asText()), as("c" + i), UUID.randomUUID().toString());
            });
            var counts = tally(replies);
            var states = new TreeMap<String, Long>();
            for (String state : List.of("CONSOLIDATED", "ACTIVE", "NOTIFIED")) {
                states.put(state, count("waitlist_entries", Criteria.where("classSessionId").is("s08-claim").and("state").is(state)));
            }
            System.out.println("T-08-30 " + seats + " seat(s), 10 notified entries hold + claim in parallel: " + counts + " → entries " + states);
            assertThat(counts).containsExactly(Map.entry("201", (long) seats), Map.entry("409 SEAT_TAKEN", 10L - seats));
            assertThat(states).containsEntry("CONSOLIDATED", (long) seats).containsEntry("ACTIVE", 10L - seats).containsEntry("NOTIFIED", 0L);
            assertThat(count("bookings", Criteria.where("classSessionId").is("s08-claim").and("state").is("ACTIVE"))).isEqualTo(seats);
            assertThat(session("claim").get("counters", Document.class)).containsEntry("booked", seats).containsEntry("waiting", 10 - seats);
            dispatch();
            assertThat(count("notifications", Criteria.where("code").is("N-46"))).isEqualTo(10L - seats);
        }
    }

    @Test void T_08_33_anExpiredHoldNotYetRemovedByTheTtlNeitherCountsNorConfirms() throws Exception {
        var pere = hold(as("pere"), "last", "s08-d-nit", 201);
        clock.advance(Duration.ofSeconds(31));
        assertThat(count("seat_holds", Criteria.where("_id").is(pere.path("id").asText()))).as("the TTL has not run").isEqualTo(1);
        var laura = hold(as("laura"), "last", "s08-d-duna", 201);
        assertThat(code(confirm(as("pere"), pere.path("id").asText(), null, 409))).isEqualTo("SEAT_HOLD_EXPIRED");
        confirm(as("laura"), laura.path("id").asText(), null, 201);
        assertThat(count("bookings", Criteria.where("classSessionId").is("s08-last"))).isEqualTo(1);
    }
}
