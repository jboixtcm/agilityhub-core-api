package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.census.application.*;
import com.agilityhub.core.shared.application.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real Mongo interleavings: the winner pauses after its sweep, before the transaction commits. */
class LifecycleConcurrencyIT extends BookingFixtures {
    @MockitoSpyBean CensusEvents censusEvents;
    @MockitoSpyBean com.agilityhub.core.payments.application.BillingEvents billingEvents;
    @MockitoSpyBean TransactionRetries retries;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("censusPackExpired")
    DomainEventHandler<com.agilityhub.core.clubs.census.domain.CensusEvent> expiryConsumer;
    @Autowired com.agilityhub.core.payments.application.PackBalanceService packService;
    @BeforeEach void monthly() {
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "activities");
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "activity_registrations");
        mongo.save(planDocument("s13-monthly", "MONTHLY"), "plans");
        mongo.updateMulti(Query.query(Criteria.where("clubId").is(CLUB)), new Update().set("planId", "s13-monthly"), "members");
    }
    record Gate(CountDownLatch paused, CountDownLatch release, CountDownLatch committed) { }
    Gate pause(String event) {
        var gate = new Gate(new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
        var once = new AtomicBoolean();
        doAnswer(call -> {
            if (once.compareAndSet(false, true)) {
                gate.paused().countDown();
                assertThat(gate.release().await(15, TimeUnit.SECONDS)).as("release winning transaction").isTrue();
            }
            return call.callRealMethod();
        }).when(censusEvents).emit(eq(event), anyString(), anyString(), anyMap());
        doAnswer(call -> {
            gate.release().countDown();
            assertThat(gate.committed().await(15, TimeUnit.SECONDS)).as("winner commits before retry").isTrue();
            return call.callRealMethod();
        }).when(retries).retried(anyString(), any());
        return gate;
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void T_13_09_approvalCannotMissAConcurrentClassOrActivity(boolean activity) throws Exception {
        String held = activity ? null : hold(as("joan"), "thu", "s08-d-duna", 201).path("id").asText();
        if (activity) {
            mongo.save(new Document("_id", "s13-race-activity").append("clubId", CLUB).append("date", "2026-10-08")
                    .append("startTime", "17:00").append("endTime", "18:00").append("startsAt", Date.from(local("2026-10-08T17:00")))
                    .append("endsAt", Date.from(local("2026-10-08T18:00"))).append("state", "PUBLISHED").append("type", "OTHER")
                    .append("title", new Document("values", new Document("en", "Practice")).append("defaultLocale", "en"))
                    .append("location", new Document("atClub", true)).append("waitlistEnabled", false).append("documents", List.of()).append("ringIds", List.of()).append("levelIds", List.of()).append("ringBlockIds", List.of())
                    .append("registrationFrom", "2026-10-05").append("registrationTo", "2026-10-07")
                    .append("registrationOpensAt", Date.from(NOW.minusSeconds(86400))).append("registrationClosesAt", Date.from(local("2026-10-08T16:00")))
                    .append("counters", new Document("active", 0).append("waiting", 0)).append("version", 0L), "activities");
        }
        var gate = pause("InactivityResolved");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var approval = pool.submit(() -> {
                try { return call(POST, "/inactivity-periods", Map.of("memberId", "s08-m-laura", "fromMonth", "2026-10", "toMonth", "2026-10", "overrideDeadline", true), as("admin"), 201); }
                finally { gate.committed().countDown(); }
            });
            try {
                assertThat(gate.paused().await(15, TimeUnit.SECONDS)).isTrue();
                var body = activity ? Map.of("activityId", "s13-race-activity") : Map.of("seatHoldId", held);
                var response = mvc.perform(post("/api/v1/"+(activity ? "activity-registrations" : "bookings"))
                        .header("Host", HOST).header("Idempotency-Key", UUID.randomUUID().toString()).with(as(activity ? "laura" : "joan"))
                        .contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
                gate.release().countDown(); approval.get(15, TimeUnit.SECONDS);
                assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(422);
                assertThat(mapper.readTree(response.getContentAsString()).path("code").asText()).isEqualTo("INACTIVITY_PERIOD");
                assertThat(count(activity ? "activity_registrations" : "bookings", Criteria.where("state").is("ACTIVE"))).isZero();
            } finally { gate.release().countDown(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"inactivity-periods", "leave-requests"})
    void T_13_26_differentKeysRetryToTheBusinessConflictAndReplayTheWinner(String resource) throws Exception {
        boolean inactivity = resource.equals("inactivity-periods");
        var body = inactivity ? Map.of("fromMonth", "2026-11", "toMonth", "2026-12") : Map.of("requestedDate", "2026-10-31", "reasonKey", "EXTERNAL");
        String path = "/me/"+resource, key = UUID.randomUUID().toString();
        var gate = pause(inactivity ? "InactivityRequested" : "LeaveRequested");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var winner = pool.submit(() -> { try { return call(POST, path, body, as("laura"), 201, key); } finally { gate.committed().countDown(); } });
            try {
                assertThat(gate.paused().await(15, TimeUnit.SECONDS)).isTrue();
                var response = mvc.perform(post("/api/v1"+path).header("Host", HOST).header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(as("laura")).contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
                gate.release().countDown(); var first = winner.get(15, TimeUnit.SECONDS);
                assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
                assertThat(mapper.readTree(response.getContentAsString()).path("code").asText()).isEqualTo(inactivity ? "INACTIVITY_OVERLAP" : "LEAVE_ALREADY_REQUESTED");
                assertThat(call(POST, path, body, as("laura"), 201, key)).isEqualTo(first);
                assertThat(count(inactivity ? "inactivity_periods" : "leave_requests", new Criteria())).isEqualTo(1);
            } finally { gate.release().countDown(); }
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void T_13_26_packExpiryAndDirectLeaveCommitOnePlannedLeave(boolean expiryFirst) throws Exception {
        openPack("s08-m-laura", "s08-d-duna", 10, 0, LocalDate.parse("2026-10-05"));
        String packId = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "pack_balances").getString("_id");
        try (var tenant = TenantContext.open(CLUB)) { packService.expire(packId); }
        var gate = pause("LeaveResolved");
        var storedEvent = eventsOf("PackExpired").getFirst();
        var expiryEvent = mapper.readValue(storedEvent.getString("eventJson"), com.agilityhub.core.clubs.census.domain.CensusEvent.class);
        Callable<Object> expiry = () -> { try (var tenant = TenantContext.open(CLUB)) {
            expiryConsumer.handle(storedEvent.getString("_id"), expiryEvent); return null;
        } };
        Callable<Object> direct = () -> mvc.perform(post("/api/v1/members/s08-m-laura/leave").header("Host", HOST).with(as("admin"))
                .contentType("application/json").content("{\"effectiveDate\":\"2026-10-31\"}")).andReturn().getResponse();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var winner = pool.submit(() -> { try { return (expiryFirst ? expiry : direct).call(); } finally { gate.committed().countDown(); } });
            try {
                assertThat(gate.paused().await(15, TimeUnit.SECONDS)).isTrue();
                Object loser = (expiryFirst ? direct : expiry).call();
                gate.release().countDown(); winner.get(15, TimeUnit.SECONDS);
                if (expiryFirst) { assertThat(((org.springframework.mock.web.MockHttpServletResponse) loser).getStatus()).isEqualTo(409); }
                else { assertThat(loser).isNull(); }
                assertThat(count("leave_requests", Criteria.where("state").is("APPROVED"))).isEqualTo(1);
                assertThat(events("LeaveResolved")).isEqualTo(1);
                var member = mongo.findById("s08-m-laura", Document.class, "members");
                var leave = mongo.findById(member.getString("leaveRequestId"), Document.class, "leave_requests");
                assertThat(leave.getString("source")).isEqualTo(expiryFirst ? "PACK_EXPIRED" : "ADMIN");
            } finally { gate.release().countDown(); }
        }
    }
    @Test void T_12_07_differentAdjustmentKeysRetryToPackNegativeAndReplayTheWinner() throws Exception {
        openPack("s08-m-laura", "s08-d-duna", 10, 0, LocalDate.parse("2026-11-11"));
        String pack = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "pack_balances").getString("_id");
        var gate = pause("unused"); var once = new AtomicBoolean();
        doAnswer(call -> {
            if (once.compareAndSet(false, true)) {
                gate.paused().countDown(); assertThat(gate.release().await(15, TimeUnit.SECONDS)).isTrue();
            }
            return call.callRealMethod();
        }).when(billingEvents).publish(eq(com.agilityhub.core.payments.domain.BillingEvent.Kind.PackAdjusted), anyString(), anyMap());
        var body = Map.of("delta", -6, "reason", "Correction"); String path = "/pack-balances/"+pack+"/adjustments", key = UUID.randomUUID().toString();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var winner = pool.submit(() -> { try { return call(POST, path, body, as("admin"), 200, key); } finally { gate.committed().countDown(); } });
            try {
                assertThat(gate.paused().await(15, TimeUnit.SECONDS)).isTrue();
                var response = mvc.perform(post("/api/v1"+path).header("Host", HOST).header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(as("admin")).contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
                gate.release().countDown(); var first = winner.get(15, TimeUnit.SECONDS);
                assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(422);
                assertThat(mapper.readTree(response.getContentAsString()).path("code").asText()).isEqualTo("PACK_NEGATIVE");
                assertThat(call(POST, path, body, as("admin"), 200, key)).isEqualTo(first);
                assertThat(mongo.findById(pack, Document.class, "pack_balances").getInteger("remaining")).isEqualTo(4);
                assertThat(events("PackAdjusted")).isEqualTo(1);
                assertThat(count("audit_entries", Criteria.where("action").is("PACK_ADJUSTED"))).isEqualTo(1);
            } finally { gate.release().countDown(); }
        }
    }

}
