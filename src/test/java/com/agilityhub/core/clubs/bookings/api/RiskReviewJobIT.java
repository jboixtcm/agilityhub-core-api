package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.scheduling.application.RiskReviewJob;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * S15 R-15-12 P2 `risk-review`, R-15-12b (`ClassBelowMinimum` → N-54) and §6 `GET /risk-review` on the fictional S08 club
 * (Laura + Duna, Pere + Nit, Joan + Toby; admin «Admin», instructor Estela), `classes.minDogs = 2`, lookahead 2 days.
 */
class RiskReviewJobIT extends BookingFixtures {
    @Autowired JobRunner runner;
    @Autowired RiskReviewJob job;

    @BeforeEach void planning() {
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "class_sessions");
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "job_runs");
        mongo.remove(new Query(), "job_locks");
        // Bookings are made on Monday 05-10 (booking week W0 = [Sun 04-10 20:00, Sun 11-10 20:00)).
        clock.setInstant(local("2026-10-05T10:00"));
    }
    private JobRun review(String localTime) {
        var at = local(localTime); clock.setInstant(at);
        return runner.scheduled(CLUB, true, job, at).orElseThrow();
    }
    private List<Document> notifications(String code) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is(code)), Document.class, "notifications");
    }
    private Map<String, Long> counters(JobRun run) {
        var map = new TreeMap<String, Long>(); run.counters().forEach(e -> map.put(e.key(), ((Number) e.value()).longValue())); return map;
    }
    private Document risk(String id) { return session(id).get("risk", Document.class); }
    private void clearNotifications() { dispatch(); mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "notifications"); }

    private record Example(String b2, String b3, JobRun tuesday) { }
    /** S15 R-15-12's example (c1…c4) on the fixture's dates, reviewed on Tuesday 06-10 at 07:30. */
    private Example example() throws Exception {
        session("c1", "2026-10-06T09:30", 5, List.of()); session("c2", "2026-10-06T17:40", 5, List.of());
        session("c3", "2026-10-08T20:00", 5, List.of()); session("c4", "2026-10-08T09:30", 5, List.of());
        String b2 = book(as("laura"), "c2", "s08-d-duna").path("id").asText();
        String b3 = book(as("pere"), "c3", "s08-d-nit").path("id").asText();
        clearNotifications();
        return new Example(b2, b3, review("2026-10-06T07:30"));
    }

    @Test void T_15_13_theReviewCancelsTodayWarnsTheNextDaysOnceAndNotifiesStaffAndStudents() throws Exception {
        // Tuesday 06-10 07:30 (05:30Z): today = c1, c2 (auto-cancel); c3, c4 on Thursday are warned.
        var example = example(); String b2 = example.b2(), b3 = example.b3(); var tuesday = example.tuesday();
        assertThat(tuesday.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(tuesday.trigger()).isEqualTo(JobTrigger.SCHEDULE);
        assertThat(tuesday.scheduledFor()).isEqualTo(Instant.parse("2026-10-06T05:30:00Z"));
        assertThat(counters(tuesday)).containsEntry("reviewed", 4L).containsEntry("cancelled", 2L).containsEntry("cancelledSilent", 1L)
                .containsEntry("atRisk", 2L).containsEntry("notifiedMembers", 1L);
        assertThat(tuesday.parametersSnapshot()).contains(new JobRun.Entry("classes.minDogs", 2), new JobRun.Entry("classes.riskLookaheadDays", 2));
        assertThat(tuesday.items()).extracting(JobRun.Item::entityId, JobRun.Item::action)
                .containsExactly(tuple("s08-c1", "CANCEL"), tuple("s08-c2", "CANCEL"), tuple("s08-c4", "NOTIFY"), tuple("s08-c3", "NOTIFY"));
        for (String id : List.of("c1", "c2")) {
            assertThat(session(id).getString("state")).isEqualTo("CANCELLED");
            assertThat(session(id).get("cancellation", Document.class).getString("reason")).isEqualTo("RISK_REVIEW");
            assertThat(session(id).get("cancellation", Document.class).getString("adminText")).contains("2");
        }
        assertThat(booking(b2).getString("state")).isEqualTo("CANCELLED_BY_CLUB");
        assertThat(booking(b2).getString("cancelReason")).isEqualTo("AUTO_CANCELLED");
        var autoCancelled = eventsOf("ClassAutoCancelled");
        assertThat(autoCancelled).extracting(e -> e.getString("aggregateId")).containsExactlyInAnyOrder("s08-c1", "s08-c2");
        var c2Event = autoCancelled.stream().filter(e -> e.getString("aggregateId").equals("s08-c2")).findFirst().orElseThrow().get("payload", Document.class);
        assertThat(c2Event.getInteger("dogsCount")).isEqualTo(1);
        assertThat(c2Event.getList("affected", Document.class)).singleElement().satisfies(a -> assertThat(a.getString("bookingId")).isEqualTo(b2));
        assertThat(eventsOf("ClassCancelledByClub")).hasSize(2)
                .allSatisfy(e -> assertThat(e.get("payload", Document.class).getString("reason")).isEqualTo("RISK_REVIEW"));
        assertThat(risk("c3").getList("notifiedBookingIds", String.class)).containsExactly(b3);
        assertThat(risk("c3").get("adminNotifiedAt")).isNotNull();
        assertThat(risk("c4").getList("notifiedBookingIds", String.class)).isEmpty();
        assertThat(risk("c4").get("adminNotifiedAt")).isNotNull();
        var atRisk = eventsOf("ClassAtRisk");
        assertThat(atRisk).hasSize(2);
        var c3Event = atRisk.stream().filter(e -> e.getString("aggregateId").equals("s08-c3")).findFirst().orElseThrow().get("payload", Document.class);
        assertThat(c3Event.getList("newBookingIds", String.class)).containsExactly(b3);
        assertThat(c3Event.getBoolean("notifyAdmins")).isTrue();
        assertThat(c3Event.getString("reviewAt")).isEqualTo("2026-10-08T05:30:00Z");

        dispatch();
        // N-17 on every auto-cancellation (c1 with 0 registrants too) to the admin and the class instructor, APP + EMAIL.
        assertThat(notifications("N-17")).hasSize(8).extracting(n -> n.getString("accountId")).containsOnly("s08-admin", "s08-inst");
        // N-08a only to Laura (c2), APP + EMAIL + SMS intent; c1 is silent towards the students.
        assertThat(notifications("N-08a")).extracting(n -> n.getString("accountId") + ":" + n.getString("channel"))
                .containsExactlyInAnyOrder("s08-laura:APP", "s08-laura:EMAIL", "s08-laura:SMS");
        var laura = notifications("N-08a").stream().filter(n -> n.getString("channel").equals("APP")).findFirst().orElseThrow();
        assertThat(laura.get("variables", Document.class).getString("admin_text")).contains("2");
        assertThat(laura.get("variables", Document.class)).containsEntry("action", "CHANGE_CLASS").containsEntry("dog_name", "Duna");
        assertThat(notifications("N-17")).filteredOn(n -> n.getString("channel").equals("APP") && "s08-c1".equals(n.get("variables", Document.class).getString("entityId")))
                .isNotEmpty().allSatisfy(n -> assertThat(n.get("variables", Document.class)).containsEntry("dogs_count", 0).doesNotContainKey("review_time")
                        .containsKeys("class_description", "ring_name"));
        // N-16: Pere (APP + EMAIL) for c3, the admins (APP) for c3 and c4.
        assertThat(notifications("N-16")).extracting(n -> n.getString("accountId") + ":" + n.getString("channel"))
                .containsExactlyInAnyOrder("s08-pere:APP", "s08-pere:EMAIL", "s08-admin:APP", "s08-admin:APP");
        var pere = notifications("N-16").stream().filter(n -> n.getString("accountId").equals("s08-pere") && n.getString("channel").equals("APP")).findFirst().orElseThrow();
        assertThat(pere.get("variables", Document.class)).containsEntry("dog_name", "Nit").containsEntry("review_time", "07:30").containsEntry("auto_cancel", "true")
                .containsEntry("class_time", "20:00").containsEntry("audience", "MEMBER");
        assertThat(pere.get("variables", Document.class).getString("review_day")).isEqualTo("dijous");
        // The admins' copy uses the staff wording: no dog phrase (RiskNotificationTextsTest renders both texts in ca/es/en).
        assertThat(notifications("N-16")).filteredOn(n -> n.getString("accountId").equals("s08-admin")).hasSize(2)
                .allSatisfy(n -> assertThat(n.get("variables", Document.class)).containsEntry("audience", "STAFF").doesNotContainKey("dog_name"));
        assertThat(notifications("N-54")).isEmpty();

        // Wednesday 07:30: c3 and c4 are still at risk, everybody already warned → no new N-16.
        var wednesday = review("2026-10-07T07:30");
        assertThat(counters(wednesday)).containsEntry("atRisk", 2L).doesNotContainKey("notifiedMembers").doesNotContainKey("cancelled");
        assertThat(wednesday.items()).isEmpty();
        dispatch();
        assertThat(notifications("N-16")).hasSize(4);

        // Thursday 07:30: both are cancelled; c4 silently (N-17 yes, N-08a no), c3 with N-08a to Pere.
        var thursday = review("2026-10-08T07:30");
        assertThat(counters(thursday)).containsEntry("cancelled", 2L).containsEntry("cancelledSilent", 1L);
        dispatch();
        assertThat(notifications("N-17")).hasSize(16);
        assertThat(notifications("N-08a")).extracting(n -> n.getString("accountId")).containsOnly("s08-laura", "s08-pere").hasSize(6);
        assertThat(session("c3").getString("state")).isEqualTo("CANCELLED");
        assertThat(session("c4").getString("state")).isEqualTo("CANCELLED");
    }

    @Test void T_15_15_riskReviewServesFormAWithStatusesAndNamesForStaffOnlyAndPerClub() throws Exception {
        example();
        dispatch();
        clock.setInstant(local("2026-10-06T08:00"));
        var form = call(GET, "/risk-review", null, as("admin"), 200);
        assertThat(form.path("reviewTime").asText()).isEqualTo("07:30");
        assertThat(form.path("lookaheadDays").asInt()).isEqualTo(2);
        assertThat(form.path("minDogs").asInt()).isEqualTo(2);
        assertThat(form.path("autoCancelSameDay").asBoolean()).isTrue();
        assertThat(form.path("items")).extracting(i -> i.path("classId").asText() + ":" + i.path("status").asText() + ":" + i.path("dayLabel").asText())
                .containsExactly("s08-c1:AUTO_CANCELLED:TODAY", "s08-c2:AUTO_CANCELLED:TODAY", "s08-c4:WILL_CANCEL:OTHER", "s08-c3:AT_RISK:OTHER");
        JsonNode c2Item = form.path("items").get(1);
        assertThat(c2Item.path("notified").get(0).path("memberName").asText()).isEqualTo("Laura");
        assertThat(c2Item.path("notified").get(0).path("dogName").asText()).isEqualTo("Duna");
        assertThat(c2Item.path("bookedCount").asInt()).isEqualTo(1);
        assertThat(c2Item.path("cancelledAt").asText()).isEqualTo("2026-10-06T05:30:00Z");
        assertThat(form.path("items").get(3).path("notified").get(0).path("dogName").asText()).isEqualTo("Nit");
        assertThat(form.path("items").get(3).path("reviewAt").asText()).isEqualTo("2026-10-08T05:30:00Z");
        assertThat(call(GET, "/risk-review", null, as("inst"), 200).path("items")).hasSize(4);
        assertThat(call(GET, "/risk-review?date=2026-10-08", null, as("admin"), 200).path("items")).extracting(i -> i.path("dayLabel").asText())
                .containsExactly("TODAY", "TODAY");
        call(GET, "/risk-review", null, as("laura"), 403);
        mongo.save(new com.agilityhub.core.identity.persistence.Membership("s08-other-admin", "s08-admin", OTHER, null, Set.of(com.agilityhub.core.identity.domain.Role.ADMIN),
                com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, com.agilityhub.core.identity.domain.Role.ADMIN));
        var other = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/risk-review").header("Host", OTHER_HOST)
                .with(jwt().jwt(j -> j.subject("s08-admin").claim("clubId", OTHER)).authorities(() -> "ROLE_ADMIN"))).andReturn().getResponse();
        assertThat(other.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(other.getContentAsString()).path("items")).isEmpty();
    }

    @Autowired com.agilityhub.core.clubs.dashboard.application.DashboardQuery dashboards;
    private static final Map<String, String> S14_STATUS = Map.of("AUTO_CANCELLED", "CANCELLED", "AT_RISK", "AT_RISK", "WILL_CANCEL", "WILL_CANCEL",
            "WILL_REVIEW", "PENDING_DECISION");
    private static List<String> texts(JsonNode array, java.util.function.Function<JsonNode, String> text) {
        var out = new ArrayList<String>(); array.forEach(node -> out.add(text.apply(node))); return out;
    }
    /** Every D1 row is the `/risk-review` row of the same class, with S14's status name. */
    private void assertParity(JsonNode form, JsonNode card) {
        assertThat(card.path("count").asInt()).isEqualTo(form.path("items").size());
        assertThat(card.path("reviewTime").asText()).isEqualTo(form.path("reviewTime").asText());
        assertThat(card.path("lookaheadDays").asInt()).isEqualTo(form.path("lookaheadDays").asInt());
        assertThat(card.path("autoCancelSameDay").asBoolean()).isEqualTo(form.path("autoCancelSameDay").asBoolean());
        assertThat(card.path("items")).extracting(i -> i.path("classSessionId").asText())
                .containsExactlyElementsOf(texts(form.path("items"), i -> i.path("classId").asText()));
        for (int i = 0; i < form.path("items").size(); i++) {
            JsonNode a = form.path("items").get(i), d = card.path("items").get(i);
            assertThat(d.path("status").asText()).as(a.path("classId").asText()).isEqualTo(S14_STATUS.get(a.path("status").asText()));
            assertThat(d.path("booked").asInt()).isEqualTo(a.path("bookedCount").asInt());
            assertThat(d.path("date").asText()).isEqualTo(a.path("date").asText());
            assertThat(d.path("startTime").asText()).isEqualTo(a.path("startTime").asText());
            assertThat(d.path("displayDescription").asText()).isEqualTo(a.path("displayDescription").asText());
            assertThat(d.path("notified")).extracting(n -> n.path("memberFirstName").asText() + "+" + n.path("dogName").asText())
                    .containsExactlyElementsOf(texts(a.path("notified"), n -> n.path("memberName").asText() + "+" + n.path("dogName").asText()));
            if (!a.path("reviewAt").isMissingNode() && !a.path("reviewAt").isNull()) { assertThat(d.path("reviewAt").asText()).isEqualTo(a.path("reviewAt").asText()); }
        }
    }

    @Test void T_15_15_T_14_05_theDashboardRiskCardIsTheRiskReviewFormA() throws Exception {
        // Around the S15 §6 example: a club cancellation, an exempt class, a class 3 days ahead, a class at the minimum and a class
        // that started before the review (skippedStarted) must be absent from both.
        session("manual", "2026-10-06T12:00", 5, List.of());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-manual")), new Update().set("state", "CANCELLED").set("cancellation",
                new Document("reason", "CLUB_MANUAL").append("at", java.util.Date.from(local("2026-10-05T09:00"))).append("affectedBookings", 0).append("affectedWaitlist", 0)),
                "class_sessions");
        session("exempt", "2026-10-07T18:00", 5, List.of());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-exempt")), new Update().set("risk.exempt", true), "class_sessions");
        session("outside", "2026-10-09T18:00", 5, List.of());
        session("safe", "2026-10-07T19:00", 5, List.of()); book(as("c0"), "safe", "s08-d-c0"); book(as("c1"), "safe", "s08-d-c1");
        session("early", "2026-10-06T07:00", 5, List.of()); book(as("c2"), "early", "s08-d-c2");
        var tuesday = example().tuesday();
        assertThat(counters(tuesday)).containsEntry("skippedStarted", 1L).containsEntry("exempt", 1L);
        assertThat(session("early").getString("state")).isEqualTo("ACTIVE");
        dispatch();
        clock.setInstant(local("2026-10-06T08:00"));
        var form = call(GET, "/risk-review", null, as("admin"), 200);
        var card = call(GET, "/dashboard", null, as("admin"), 200).path("riskReview");
        // c4 (0 registrants, admins already warned) is WILL_CANCEL in both (S15 §6), c3 AT_RISK with Pere + Nit, c1/c2 cancelled.
        assertThat(form.path("items")).extracting(i -> i.path("classId").asText() + ":" + i.path("status").asText())
                .containsExactly("s08-c1:AUTO_CANCELLED", "s08-c2:AUTO_CANCELLED", "s08-c4:WILL_CANCEL", "s08-c3:AT_RISK");
        assertThat(card.path("items")).extracting(i -> i.path("classSessionId").asText() + ":" + i.path("status").asText())
                .containsExactly("s08-c1:CANCELLED", "s08-c2:CANCELLED", "s08-c4:WILL_CANCEL", "s08-c3:AT_RISK");
        assertThat(card.path("items").get(3).path("notified").get(0).path("dogName").asText()).isEqualTo("Nit");
        assertThat(card.path("items").get(2).path("reviewAt").asText()).isEqualTo("2026-10-08T05:30:00Z");
        assertParity(form, card);
        // With riskAutoCancelSameDay = false: c4 is WILL_REVIEW in form A and PENDING_DECISION on D1.
        // Stays: the fixture writes the parameter straight into Mongo, so no ParameterChanged reaches D1's consumer.
        parameter("classes.riskAutoCancelSameDay", false); dashboards.invalidate(CLUB);
        form = call(GET, "/risk-review", null, as("admin"), 200);
        card = call(GET, "/dashboard", null, as("admin"), 200).path("riskReview");
        assertThat(form.path("items").get(2).path("status").asText()).isEqualTo("WILL_REVIEW");
        assertThat(card.path("items").get(2).path("status").asText()).isEqualTo("PENDING_DECISION");
        assertParity(form, card);
    }

    /**
     * E5-T15, ruling E37 (S15 §6, amended 24-09): D1 never says «s'anul·larà» about a review that will not run. A class
     * today at 17:40 with 0 registrants, read at 10:00 (today's 07:30 review is over) → AT_RISK; tomorrow's class before
     * its review → WILL_CANCEL; with the `risk-review` job switched off → AT_RISK. Form A and D1 agree.
     * E5-T17 (review E5-T15 #1, S15 §6 amended 25-09): tomorrow's 07:00 and 07:30 classes start before or at their own
     * 07:30 review, which skips them as started → AT_RISK.
     */
    @Test void T_15_15_E37_willCancelOnlyWhenTheRiskReviewWillReallyRunForTheClass() throws Exception {
        session("late", "2026-10-06T17:40", 5, List.of()); session("early", "2026-10-07T07:00", 5, List.of());
        session("edge", "2026-10-07T07:30", 5, List.of()); session("next", "2026-10-07T18:00", 5, List.of());
        clock.setInstant(local("2026-10-06T10:00"));
        var form = call(GET, "/risk-review", null, as("admin"), 200);
        assertThat(form.path("items")).extracting(i -> i.path("classId").asText() + ":" + i.path("status").asText() + ":" + i.path("bookedCount").asInt())
                .containsExactly("s08-late:AT_RISK:0", "s08-early:AT_RISK:0", "s08-edge:AT_RISK:0", "s08-next:WILL_CANCEL:0");
        assertThat(form.path("items").get(0).path("notified")).isEmpty();
        assertThat(form.path("items").get(0).path("reviewAt").asText()).isEqualTo("2026-10-06T05:30:00Z");
        assertThat(form.path("items").get(1).path("reviewAt").asText()).isEqualTo("2026-10-07T05:30:00Z");
        // Stays: the classes above are written straight into Mongo (no event), so a D1 cached by an earlier test would hide them.
        dashboards.invalidate(CLUB);
        var card = call(GET, "/dashboard", null, as("admin"), 200).path("riskReview");
        assertThat(card.path("items")).extracting(i -> i.path("classSessionId").asText() + ":" + i.path("status").asText())
                .containsExactly("s08-late:AT_RISK", "s08-early:AT_RISK", "s08-edge:AT_RISK", "s08-next:WILL_CANCEL");
        assertParity(form, card);
        // Before today's review the 17:40 class is still WILL_CANCEL; tomorrow's 07:00 and 07:30 ones are not.
        clock.setInstant(local("2026-10-06T07:00"));
        assertThat(call(GET, "/risk-review", null, as("admin"), 200).path("items")).extracting(i -> i.path("status").asText())
                .containsExactly("WILL_CANCEL", "AT_RISK", "AT_RISK", "WILL_CANCEL");
        // The job switched off: nothing will cancel any class.
        call(org.springframework.http.HttpMethod.PUT, "/jobs/risk-review/switch", Map.of("enabled", false), as("admin"), 200);
        form = call(GET, "/risk-review", null, as("admin"), 200);
        assertThat(form.path("items")).extracting(i -> i.path("classId").asText() + ":" + i.path("status").asText())
                .containsExactly("s08-late:AT_RISK", "s08-early:AT_RISK", "s08-edge:AT_RISK", "s08-next:AT_RISK");
        // E5-T17 step 2 (review E5-T15 #2, S14 §7 amended 25-09): the switch is a `jobs.riskReview.enabled` write, whose
        // ParameterChanged evicts D1 once the outbox delivers it. No manual invalidation: the card cached above (next =
        // WILL_CANCEL) must go.
        assertThat(eventsOf("ParameterChanged")).extracting(e -> e.getString("aggregateId")).contains("jobs.riskReview.enabled");
        dispatch();
        card = call(GET, "/dashboard", null, as("admin"), 200).path("riskReview");
        assertThat(card.path("items")).extracting(i -> i.path("classSessionId").asText() + ":" + i.path("status").asText())
                .containsExactly("s08-late:AT_RISK", "s08-early:AT_RISK", "s08-edge:AT_RISK", "s08-next:AT_RISK");
        assertParity(form, card);
    }

    @Test void T_15_14_exemptSameDayOffMinimumStartedPendingDraftAndStaleCounters() throws Exception {
        session("exempt", "2026-10-06T09:00", 5, List.of());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-exempt")), new Update().set("risk.exempt", true), "class_sessions");
        session("draft", "2026-10-06T10:00", 5, List.of());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-draft")), new Update().set("state", "DRAFT"), "class_sessions");
        session("started", "2026-10-06T17:40", 5, List.of()); session("evening", "2026-10-06T20:00", 5, List.of());
        session("stale", "2026-10-07T18:00", 5, List.of()); session("pending", "2026-10-07T19:00", 5, List.of());
        session("three", "2026-10-07T20:00", 5, List.of());
        book(as("laura"), "stale", "s08-d-duna");
        // counters.booked out of sync (3) with one real booking: the review recounts.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-stale")), new Update().set("counters.booked", 3), "class_sessions");
        book(as("pere"), "pending", "s08-d-nit");
        var now = clock.instant(); var starts = local("2026-10-07T19:00");
        mongo.insert(new Booking("s08-pending-b", CLUB, "s08-pending", "s08-d-toby", "s08-m-joan", BookingState.PAYMENT_PENDING, BookingOrigin.APP, now,
                new Booking.Actor("s08-joan", null, "Joan"), starts, starts.plusSeconds(3600), "2026-10-04", null, null, null, null, null, null, null, null,
                null, null, null, null, null, 1L, now, "s08-joan", now, "s08-joan"));
        book(as("laura"), "three", "s08-d-rock"); book(as("joan"), "three", "s08-d-toby");
        clearNotifications();

        // A late run (CATCH_UP at 18:00) never cancels a class that has started; the 20:00 one is cancelled.
        var late = review("2026-10-06T18:00");
        assertThat(late.trigger()).isEqualTo(JobTrigger.CATCH_UP);
        assertThat(counters(late)).containsEntry("exempt", 1L).containsEntry("skippedStarted", 1L).containsEntry("cancelled", 1L);
        assertThat(session("started").getString("state")).isEqualTo("ACTIVE");
        assertThat(session("evening").getString("state")).isEqualTo("CANCELLED");
        assertThat(session("exempt").getString("state")).isEqualTo("ACTIVE");
        assertThat(session("draft").getString("state")).isEqualTo("DRAFT");
        assertThat(late.items()).extracting(JobRun.Item::entityId).doesNotContain("s08-exempt", "s08-draft", "s08-pending", "s08-three")
                .contains("s08-stale");
        // PAYMENT_PENDING counts as a dog: «pending» (1 ACTIVE + 1 PAYMENT_PENDING) is not at risk; «stale» is treated as 1.
        assertThat(risk("stale").getList("notifiedBookingIds", String.class)).hasSize(1);

        // minDogs = 3 → «three» (2 registrants) is at risk; with riskAutoCancelSameDay = false today only warns, auto_cancel = false.
        // «dawn» (Wednesday 07:00, one registrant) has started when Wednesday's review runs: with riskAutoCancelSameDay = false it is
        // neither cancelled nor warned either (an evening CATCH_UP or manual run never sends N-16 for a class that has begun).
        session("dawn", "2026-10-07T07:00", 5, List.of()); book(as("c3"), "dawn", "s08-d-c3");
        parameter("classes.minDogs", 3); parameter("classes.riskAutoCancelSameDay", false);
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "job_runs");
        clearNotifications();
        var sameDayOff = review("2026-10-07T07:30");
        assertThat(sameDayOff.items()).extracting(JobRun.Item::action).containsOnly("NOTIFY");
        assertThat(sameDayOff.items()).extracting(JobRun.Item::entityId).doesNotContain("s08-dawn");
        assertThat(counters(sameDayOff)).containsEntry("skippedStarted", 1L);
        assertThat(session("dawn").getString("state")).isEqualTo("ACTIVE");
        assertThat(risk("dawn").getList("notifiedBookingIds", String.class)).isEmpty(); assertThat(risk("dawn").get("adminNotifiedAt")).isNull();
        assertThat(session("three").getString("state")).isEqualTo("ACTIVE");
        assertThat(risk("three").getList("notifiedBookingIds", String.class)).hasSize(2);
        dispatch();
        assertThat(notifications("N-16")).filteredOn(n -> n.getString("accountId").equals("s08-laura") && n.getString("channel").equals("APP"))
                .isNotEmpty().allSatisfy(n -> assertThat(n.get("variables", Document.class).getString("auto_cancel")).isEqualTo("false"));
        assertThat(notifications("N-16")).noneMatch(n -> n.getString("accountId").equals("s08-c3") || n.get("variables", Document.class) != null && "s08-dawn".equals(n.get("variables", Document.class).getString("entityId")));
        // Form A leaves it out too: no review will act on it.
        assertThat(call(GET, "/risk-review", null, as("admin"), 200).path("items")).noneMatch(i -> i.path("classId").asText().equals("s08-dawn"));
        // A new registrant of a warned class is warned alone on the next review.
        clock.setInstant(local("2026-10-07T08:00"));
        String newcomer = book(as("pere"), "three", "s08-d-nit").path("id").asText();
        parameter("classes.minDogs", 4);
        clearNotifications();
        var next = runner.manual(CLUB, JobName.RISK_REVIEW, false, "s08-admin");
        assertThat(next.trigger()).isEqualTo(JobTrigger.MANUAL);
        dispatch();
        assertThat(notifications("N-16")).filteredOn(n -> !n.getString("accountId").equals("s08-admin"))
                .extracting(n -> n.getString("accountId")).containsOnly("s08-pere");
        assertThat(risk("three").getList("notifiedBookingIds", String.class)).contains(newcomer).hasSize(3);
    }

    @Test void T_15_06_dryRunPlanMatchesTheRealRunAndWritesNothing() throws Exception {
        session("c1", "2026-10-06T09:30", 5, List.of()); session("c2", "2026-10-06T17:40", 5, List.of()); session("c3", "2026-10-08T20:00", 5, List.of());
        book(as("laura"), "c2", "s08-d-duna"); book(as("pere"), "c3", "s08-d-nit");
        dispatch();
        clock.setInstant(local("2026-10-06T07:30"));
        long events = mongo.count(new Query(), "domain_events"), sessions = mongo.count(Query.query(Criteria.where("state").is("CANCELLED")), "class_sessions");
        var dry = runner.manual(CLUB, JobName.RISK_REVIEW, true, "s08-admin");
        assertThat(mongo.count(new Query(), "domain_events")).isEqualTo(events);
        assertThat(mongo.count(Query.query(Criteria.where("state").is("CANCELLED")), "class_sessions")).isEqualTo(sessions);
        assertThat(dry.items()).extracting(JobRun.Item::action).containsExactly("WOULD_CANCEL", "WOULD_CANCEL", "WOULD_NOTIFY");
        var real = runner.manual(CLUB, JobName.RISK_REVIEW, false, "s08-admin");
        assertThat(real.items()).extracting(JobRun.Item::entityId).containsExactlyElementsOf(dry.items().stream().map(JobRun.Item::entityId).toList());
        assertThat(real.items()).extracting(JobRun.Item::action)
                .containsExactlyElementsOf(dry.items().stream().map(i -> i.action().substring("WOULD_".length())).toList());
        assertThat(real.items()).extracting(JobRun.Item::detail).containsExactlyElementsOf(dry.items().stream().map(JobRun.Item::detail).toList());
        // R-15-04: a second execution finds nothing new (c1/c2 are CANCELLED, c3 already warned).
        var again = runner.manual(CLUB, JobName.RISK_REVIEW, false, "s08-admin");
        assertThat(again.items()).isEmpty();
        System.out.println("E5-T05 risk-review JobRun dry  " + mongo.findById(dry.id(), Document.class, "job_runs").toJson());
        System.out.println("E5-T05 risk-review JobRun real " + mongo.findById(real.id(), Document.class, "job_runs").toJson());
    }

    @Test void T_15_04_riskReviewCatchesUpUntilTheEndOfTheLocalDayThenMissesItsWindow() {
        session("c1", "2026-10-06T23:59", 5, List.of());
        var catchUp = review("2026-10-06T23:58");
        assertThat(catchUp.trigger()).isEqualTo(JobTrigger.CATCH_UP);
        assertThat(catchUp.scheduledFor()).isEqualTo(Instant.parse("2026-10-06T05:30:00Z"));
        // The next day's occurrence (07-10 07:30), checked at 00:01 on the day after it: the catch-up above is the process's
        // history, so this miss is a lost run and alerts (a first run without history would be the silent E33 baseline).
        var missed = review("2026-10-08T00:01");
        assertThat(missed.scheduledFor()).isEqualTo(Instant.parse("2026-10-07T05:30:00Z"));
        assertThat(missed.status()).isEqualTo(JobStatus.SKIPPED);
        assertThat(missed.skipReason()).isEqualTo(SkipReason.MISSED_WINDOW);
        assertThat(eventsOf("JobFailed")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class).getString("job")).isEqualTo("RISK_REVIEW"));
        dispatch();
        assertThat(notifications("N-42")).extracting(n -> n.getString("accountId")).contains("s08-admin");
    }

    @Test void R_15_12b_anInTimeDropBelowTheMinimumAlertsStaffOnceAndClearsWhenRecovered() throws Exception {
        session("thu", "2026-10-08T18:50", 5, List.of());
        String laura = book(as("laura"), "thu", "s08-d-duna").path("id").asText();
        String pere = book(as("pere"), "thu", "s08-d-nit").path("id").asText();
        clearNotifications();
        // Wednesday 10:00: Laura cancels in time → 1 dog < 2 → ClassBelowMinimum → N-54 to the instructor and the admins, nothing else.
        clock.setInstant(local("2026-10-07T10:00"));
        cancel(as("laura"), laura, 200);
        assertThat(eventsOf("ClassBelowMinimum")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class))
                .containsEntry("classId", "s08-thu").containsEntry("countedDogs", 1).containsEntry("minDogs", 2));
        assertThat(session("thu").get("risk", Document.class).get("lowAlertSentAt")).isNotNull();
        dispatch();
        assertThat(notifications("N-54")).extracting(n -> n.getString("accountId") + ":" + n.getString("channel"))
                .containsExactlyInAnyOrder("s08-admin:APP", "s08-admin:EMAIL", "s08-inst:APP", "s08-inst:EMAIL");
        var variables = notifications("N-54").getFirst().get("variables", Document.class);
        assertThat(variables).containsEntry("class_time", "18:50").containsEntry("ring_name", "Central").containsEntry("dogs_count", 1).containsEntry("action", "CHANGE_CLASS");
        assertThat(notifications("N-54")).noneMatch(n -> n.getString("accountId").equals("s08-pere") || n.getString("accountId").equals("s08-laura"));
        assertThat(session("thu").getString("state")).isEqualTo("ACTIVE");
        assertThat(eventsOf("ClassAtRisk")).isEmpty();
        // A second in-time cancellation while the first alert stands sends nothing.
        cancel(as("pere"), pere, 200);
        dispatch();
        assertThat(eventsOf("ClassBelowMinimum")).hasSize(1);
        assertThat(notifications("N-54")).hasSize(4);
        // Back to the minimum clears the mark; a new drop alerts again.
        book(as("joan"), "thu", "s08-d-toby"); String rock = book(as("laura"), "thu", "s08-d-rock").path("id").asText();
        assertThat(session("thu").get("risk", Document.class).get("lowAlertSentAt")).isNull();
        cancel(as("laura"), rock, 200);
        assertThat(eventsOf("ClassBelowMinimum")).hasSize(2);
        // The startsAt guard (nothing for a class that has begun) is asserted on P6's path: BookingJobsIT.R_15_12b_….
        dispatch();
        assertThat(notifications("N-54")).hasSize(8);
    }
}
