package com.agilityhub.core.clubs.training.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** S09 R-09-05/06/07 under concurrency: parallel executors against the real Mongo replica set and the partial unique seat index. */
class TrainingConcurrencyIT extends TrainingFixtures {
    record Reply(int status, JsonNode body) { String code() { return body.path("code").asText(); } }
    Reply send(Object body, RequestPostProcessor auth) throws Exception {
        var request = post("/api/v1/training-bookings").header("Host", HOST).with(auth).contentType("application/json").content(mapper.writeValueAsBytes(body))
                .header("Idempotency-Key", UUID.randomUUID().toString());
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
    static Map<String, Object> body(String dogId, String localStart, String ringId) {
        var body = new LinkedHashMap<String, Object>(); body.put("dogId", dogId); body.put("startsAt", local(localStart).toString()); if (ringId != null) { body.put("ringId", ringId); }
        return body;
    }

    @Test void T_09_32_twentyDogsOnOneCapacityOneSlotLeaveExactlyOneBookingAndCapacityTwoLeavesTwo() throws Exception {
        var replies = parallel(20, i -> () -> send(body("s09-d-c" + i, "2026-10-05T10:00", MUN), as("c" + i)));
        var counts = tally(replies);
        System.out.println("T-09-32 capacity 1: " + counts);
        assertThat(counts).containsExactly(Map.entry("201", 1L), Map.entry("409 SLOT_TAKEN", 19L));
        assertThat(count("training_bookings", Criteria.where("ringId").is(MUN).and("state").is("ACTIVE"))).isEqualTo(1);
        assertThat(replies.stream().filter(r -> r.status() == 409).allMatch(r -> r.body().at("/details/reason").asText().equals("TRAINING")
                && r.body().at("/details/freeRings").size() == 3)).isTrue();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CEN)), new Update().set("trainingCapacity", 2), "rings");
        var two = parallel(20, i -> () -> send(body("s09-d-c" + i, "2026-10-05T11:00", CEN), as("c" + i)));
        var twoCounts = tally(two);
        System.out.println("T-09-32 capacity 2: " + twoCounts);
        assertThat(twoCounts).containsExactly(Map.entry("201", 2L), Map.entry("409 SLOT_TAKEN", 18L));
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("ringId").is(CEN).and("state").is("ACTIVE")), org.bson.Document.class, "training_bookings"))
                .extracting(d -> d.getInteger("seatIndex")).containsExactlyInAnyOrder(0, 1);
    }

    @Test void T_09_33_fiveParallelBookingsOfADogAtTwoOfThreeLeaveOneAndFourLimitReached() throws Exception {
        book(as("maria"), "s09-d-rock", "2026-10-05T09:00", MUN, 201);
        book(as("maria"), "s09-d-rock", "2026-10-05T09:30", MUN, 201);
        var starts = List.of("2026-10-06T09:00", "2026-10-06T11:00", "2026-10-07T09:00", "2026-10-07T11:00", "2026-10-08T09:00");
        var replies = parallel(5, i -> () -> send(body("s09-d-rock", starts.get(i), CEN), as("maria")));
        var counts = tally(replies);
        System.out.println("T-09-33 Rock at 2/3, five parallel slots: " + counts);
        assertThat(counts).containsExactly(Map.entry("201", 1L), Map.entry("409 TRAINING_LIMIT_REACHED", 4L));
        assertThat(count("training_bookings", Criteria.where("dogId").is("s09-d-rock").and("state").is("ACTIVE"))).isEqualTo(3);
        System.out.println("T-09-33 Dog.trainingSeq after the run: " + mongo.findById("s09-d-rock", org.bson.Document.class, "dogs").get("trainingSeq"));
    }

    @Test void T_09_34_threeParallelAnyRingBookingsTakeThreeRingsInCatalogOrder() throws Exception {
        // Only three reservable rings for this scenario.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CAD)), new Update().set("allowsFreeTraining", false), "rings");
        for (int round = 0; round < 3; round++) {
            String start = "2026-10-0" + (6 + round) + "T10:00"; final int base = round * 3;
            var replies = parallel(3, i -> () -> send(body("s09-d-c" + (base + i), start, null), as("c" + (base + i))));
            var rings = replies.stream().filter(r -> r.status() == 201).map(r -> r.body().path("ringId").asText()).sorted().toList();
            System.out.println("T-09-34 round " + round + " («Qualsevol» ×3): " + tally(replies) + " rings " + rings);
            assertThat(tally(replies)).containsExactly(Map.entry("201", 3L));
            assertThat(rings).containsExactlyInAnyOrder(MUN, CEN, CAR);
            var byTime = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("startsAt").is(java.util.Date.from(local(start)))), org.bson.Document.class, "training_bookings")
                    .stream().sorted(Comparator.comparing(d -> d.getDate("createdAt"))).map(d -> d.getString("ringId")).toList();
            assertThat(byTime).hasSize(3).doesNotHaveDuplicates();
        }
        var fourth = send(body("s09-d-c19", "2026-10-06T10:00", null), as("c19"));
        assertThat(fourth.status()).isEqualTo(409); assertThat(fourth.code()).isEqualTo("SLOT_TAKEN");
    }
}
