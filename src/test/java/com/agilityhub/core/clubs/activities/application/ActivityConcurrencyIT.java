package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.domain.RegistrationState;
import com.agilityhub.core.clubs.activities.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.support.ConcurrencySupport;
import com.agilityhub.core.support.ConcurrencySupport.HeldTransaction;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * E5-T07 · S07 R-07-08 on the Mongo path alone: the local lanes are off, so concurrent registrations meet only the
 * `$inc registrationSeq` write conflict and its retries (T-07-24/25).
 */
@TestPropertySource(properties = "core.concurrency.local-lanes=false")
class ActivityConcurrencyIT extends ActivityFixtures {
    @Autowired LocalLanes lanes; @Autowired TransactionRetries retries; @Autowired TransactionTemplate tx;
    record Reply(int status, JsonNode body) { String outcome() { return status + (status >= 400 ? " " + body.path("code").asText() : " " + body.path("state").asText()); } }

    Reply send(String path, Object body, String member) throws Exception {
        var response = mvc.perform(auth(post("/api/v1" + path).header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content(mapper.writeValueAsBytes(body)), member, "MEMBER")).andReturn().getResponse();
        return new Reply(response.getStatus(), response.getContentAsByteArray().length == 0 ? mapper.nullNode() : mapper.readTree(response.getContentAsString()));
    }
    static Map<String, Long> tally(List<Reply> replies) {
        var result = new TreeMap<String, Long>(); replies.forEach(r -> result.merge(r.outcome(), 1L, Long::sum)); return result;
    }
    long registrationSeq(String id) { return ((Number) mongo.findById(id, Document.class, "activities").getOrDefault("registrationSeq", 0)).longValue(); }

    @Test void T_07_24_lanesOffTwentyRegistrationsForOnePlaceLeaveExactlyOneActiveAndTheRestWaitlistedOrStale() throws Exception {
        assertThat(lanes.enabled()).isFalse();
        String id = published(1, false).path("id").asText();
        long seq = registrationSeq(id); double retried = retries.retries(ActivityTransactions.CONTEXT), exhausted = retries.exhaustions(ActivityTransactions.CONTEXT);
        var replies = ConcurrencySupport.parallel(20, i -> () -> send("/activity-registrations", Map.of("activityId", id, "joinWaitlist", true), "m" + i));
        var counts = tally(replies);
        double retriedNow = retries.retries(ActivityTransactions.CONTEXT) - retried, exhaustedNow = retries.exhaustions(ActivityTransactions.CONTEXT) - exhausted;
        System.out.println("E5-T07 T-07-24 lanes off, 20 registrations for 1 place: " + counts + " · retries " + retriedNow + " (write_conflict "
                + retries.retries(ActivityTransactions.CONTEXT, "write_conflict") + " cumulative) · exhausted " + exhaustedNow);
        assertThat(counts.keySet()).isSubsetOf("201 ACTIVE", "201 WAITLISTED", "409 STALE_VERSION");
        assertThat(counts).containsEntry("201 ACTIVE", 1L);
        long created = replies.stream().filter(r -> r.status() == 201).count(), stale = counts.getOrDefault("409 STALE_VERSION", 0L);
        assertThat(retriedNow).as("the registrationSeq write conflict was met and retried").isPositive();
        assertThat((long) exhaustedNow).isEqualTo(stale);
        assertThat(registrationSeq(id) - seq).as("one registrationSeq $inc per committed registration").isEqualTo(created);
        try (var tenant = TenantContext.open(CLUB)) {
            var live = registrations.live(id);
            assertThat(live.stream().filter(r -> r.state() == RegistrationState.ACTIVE)).hasSize(1);
            assertThat(live).hasSize((int) created);
            assertThat(live.stream().filter(r -> r.state() == RegistrationState.WAITLISTED).map(ActivityRegistration::position).sorted())
                    .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, (int) created - 1).boxed().toList());
            assertThat(activities.require(id).counters()).isEqualTo(new Activity.Counters(1, (int) created - 1));
        }
    }

    @Test void T_07_24_aRegistrationThatMeetsAHeldRegistrationSeqIncIsRetriedAndCommitsAfterIt() throws Exception {
        String id = published(1, false).path("id").asText(); long seq = registrationSeq(id);
        double before = retries.retries(ActivityTransactions.CONTEXT, "write_conflict");
        try (var held = new HeldTransaction(tx, CLUB, () -> activities.lock(id))) {
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                double base = retries.retries(ActivityTransactions.CONTEXT);
                var request = pool.submit(() -> send("/activity-registrations", Map.of("activityId", id), "m0"));
                ConcurrencySupport.awaitRetry(retries, ActivityTransactions.CONTEXT, base);
                held.commit();
                var reply = request.get();
                assertThat(reply.outcome()).isEqualTo("201 ACTIVE");
            } finally { pool.shutdownNow(); }
        }
        assertThat(retries.retries(ActivityTransactions.CONTEXT, "write_conflict")).isGreaterThan(before);
        assertThat(registrationSeq(id) - seq).as("the held $inc and the retried registration").isEqualTo(2);
    }

    @Test void T_07_25_lanesOffConcurrentCancellationsPromoteAtMostOnceAndKeepCountersCoherent() throws Exception {
        for (int round = 0; round < 3; round++) {
            String id = published(2, false).path("id").asText();
            var one = send("/activity-registrations", Map.of("activityId", id), "m0").body(); var two = send("/activity-registrations", Map.of("activityId", id), "m1").body();
            send("/activity-registrations", Map.of("activityId", id, "joinWaitlist", true), "m2");
            var replies = ConcurrencySupport.parallel(2, i -> () -> send("/activity-registrations/" + (i == 0 ? one : two).path("id").asText() + "/cancellation", Map.of(), "m" + i));
            System.out.println("E5-T07 T-07-25 lanes off, round " + round + ", two cancellations: " + tally(replies));
            assertThat(replies).allSatisfy(r -> assertThat(r.outcome()).isIn("200 CANCELLED", "409 STALE_VERSION"));
            try (var tenant = TenantContext.open(CLUB)) {
                var live = registrations.live(id);
                long active = live.stream().filter(r -> r.state() == RegistrationState.ACTIVE).count(), waiting = live.stream().filter(r -> r.state() == RegistrationState.WAITLISTED).count();
                assertThat(activities.require(id).counters()).isEqualTo(new Activity.Counters((int) active, (int) waiting));
                assertThat(live.stream().filter(r -> r.promotedAt() != null)).hasSizeLessThanOrEqualTo(1);
                long cancelled = replies.stream().filter(r -> r.status() == 200).count();
                assertThat(active).isEqualTo(Math.min(2, 3 - cancelled)).isLessThanOrEqualTo(2);
            }
        }
    }
}
