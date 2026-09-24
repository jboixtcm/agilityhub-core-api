package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.clubs.activities.persistence.*;
import com.agilityhub.core.clubs.bookings.application.BookableClassesCache;
import com.agilityhub.core.clubs.training.domain.*;
import com.agilityhub.core.clubs.training.persistence.TrainingBooking;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.util.stream.StreamSupport;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * S08 WP-08-D over real Mongo: `GET /me/home` (03) and `GET /me/bookable-classes` (04). The fixture is the S08 club of
 * {@link BookingFixtures} (Tuesday 06-10-2026 10:00 Madrid) plus one free-training booking of Rock and one activity
 * registration of Laura written as documents (their own services are covered by S09/S07 tests).
 */
class MemberAggregatesIT extends BookingFixtures {
    @Autowired BookableClassesCache cache;

    @BeforeEach void aggregates() {
        for (String collection : List.of("training_bookings", "activities", "activity_registrations")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
        cache.invalidate(CLUB); cache.invalidate(OTHER);
    }
    JsonNode home(String account, String dogId, int expected) throws Exception {
        return call(GET, "/me/home" + (dogId == null ? "" : "?dogId=" + dogId), null, as(account), expected);
    }
    JsonNode bookable(String account, String dogId, int expected) throws Exception {
        return call(GET, "/me/bookable-classes" + (dogId == null ? "" : "?dogId=" + dogId), null, as(account), expected);
    }
    JsonNode row(JsonNode classes, String id) {
        return StreamSupport.stream(classes.path("classes").spliterator(), false).filter(c -> c.path("id").asText().equals("s08-" + id)).findFirst().orElse(null);
    }
    List<String> ids(JsonNode aggregate) {
        return StreamSupport.stream(aggregate.path("classes").spliterator(), false).map(c -> c.path("id").asText().substring(4)).toList();
    }
    void training(String id, String dogId, String start) {
        var starts = local(start);
        var week = Instant.parse("2026-10-04T18:00:00Z");
        mongo.insert(new TrainingBooking(id, CLUB, "s08-m-laura", dogId, "s08-ring", starts, starts.plusSeconds(1800), "s08-ring_" + starts, 0, week,
                TrainingBookingState.ACTIVE, TrainingOrigin.APP, "s08-laura", null, null, null, null, null, null, null, 1L, NOW, NOW, "s08-laura"));
    }
    void activity(String id, String date, String memberId) {
        var a = new LinkedHashMap<String, Object>();
        a.put("id", id); a.put("clubId", CLUB); a.put("title", Map.of("ca", "Torneig fictici", "es", "Torneo ficticio")); a.put("type", "COMPETITION");
        a.put("location", Map.of("atClub", true)); a.put("ringIds", List.of("s08-ring")); a.put("date", date); a.put("startTime", "18:30"); a.put("endTime", "20:30");
        a.put("registrationFrom", "2026-09-01"); a.put("registrationTo", "2026-10-09"); a.put("maxPlaces", 20); a.put("levelIds", List.of());
        a.put("waitlistEnabled", false); a.put("state", "PUBLISHED"); a.put("counters", Map.of("active", memberId == null ? 0 : 1, "waiting", 0));
        a.put("slug", id); a.put("documents", List.of()); a.put("ringBlockIds", List.of()); a.put("version", 0);
        mongo.insert(mapper.convertValue(a, Activity.class));
        if (memberId != null) {
            mongo.insert(new ActivityRegistration(id + "-r", CLUB, id, memberId, RegistrationState.ACTIVE, RegistrationOrigin.APP, NOW,
                    new ActivityRegistration.RegisteredBy("s08-laura", null, "Laura"), 1, null, null, null, null, local(date + "T18:30"), null, 0L, NOW, "s08-laura", NOW, "s08-laura"));
        }
    }

    @Test void T_08_12_homeMergesTheFourSourcesChronologicallyWithCountersAndModules() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-laura")), new Update().set("gender", "FEMALE"), "members");
        book(as("laura"), "wed", "s08-d-duna");                      // CLASS W0, Wed 07 18:50
        book(as("laura"), "levelD", "s08-d-rock");                     // CLASS W0, Thu 08 17:40
        book(as("laura"), "mon", "s08-d-toby");                      // CLASS W1 of the group dog (booked by Laura)
        book(as("pere"), "last", "s08-d-nit");                       // the capacity-1 class is full …
        var entry = join(as("laura"), "last", "s08-d-duna", 201);    // … so Duna waits (CLASS_WAITLIST, Thu 08 20:00)
        training("s08-t-rock", "s08-d-rock", "2026-10-07T08:00");    // TRAINING, Wed 07 08:00
        activity("s08-act", "2026-10-10", "s08-m-laura");            // ACTIVITY, Sat 10 18:30
        dispatch();

        var all = home("laura", null, 200);
        assertThat(all.at("/member/firstName").asText()).isEqualTo("Laura"); assertThat(all.at("/member/gender").asText()).isEqualTo("FEMALE");
        assertThat(all.path("dogs")).extracting(d -> d.path("name").asText() + ":" + d.path("own").asBoolean() + ":" + d.path("ownerFirstName").asText(null) + ":" + d.path("levelName").asText())
                .containsExactly("Duna:true:null:C", "Rock:true:null:D", "Toby:false:Joan:C");
        assertThat(all.path("selectedDogId").isNull()).isTrue();
        assertThat(all.at("/limits/unit").asText()).isEqualTo("DOG");
        assertThat(all.at("/limits/currentWeek/count").asInt()).isEqualTo(2); assertThat(all.at("/limits/currentWeek/max").asInt()).isEqualTo(2);
        assertThat(all.at("/limits/currentWeek/weekKey").asText()).isEqualTo("2026-10-04");
        assertThat(all.at("/limits/nextWeek/count").asInt()).isEqualTo(1); assertThat(all.at("/limits/nextWeek/max").asInt()).isEqualTo(1);
        assertThat(all.at("/limits/nextWeek/weekKey").asText()).isEqualTo("2026-10-11");
        assertThat(all.path("reservations")).extracting(r -> r.path("type").asText() + ":" + r.path("dogName").asText(null) + ":" + r.path("startsAtLocal").asText())
                .containsExactly("TRAINING:Rock:2026-10-07T08:00", "CLASS:Duna:2026-10-07T18:50", "CLASS:Rock:2026-10-08T17:40",
                        "CLASS_WAITLIST:Duna:2026-10-08T20:00", "ACTIVITY:null:2026-10-10T18:30", "CLASS:Toby:2026-10-12T18:50");
        JsonNode training = all.at("/reservations/0"), wed = all.at("/reservations/1"), waiting = all.at("/reservations/3"), activity = all.at("/reservations/4");
        assertThat(training.path("state").asText()).isEqualTo("CONFIRMED"); assertThat(training.path("title").asText()).isEqualTo("Entrenament");
        assertThat(training.path("endsAtLocal").asText()).isEqualTo("2026-10-07T08:30"); assertThat(training.path("ringName").asText()).isEqualTo("Central");
        assertThat(wed.path("state").asText()).isEqualTo("CONFIRMED"); assertThat(wed.path("title").asText()).isEqualTo("Classe Classe wed");
        assertThat(wed.path("endsAtLocal").asText()).isEqualTo("2026-10-07T19:50"); assertThat(wed.path("ringName").asText()).isEqualTo("Central");
        // R-08-20: 32 h before the class the instructor is hidden and the row says when it shows (24 h before).
        assertThat(wed.path("instructorName").isNull()).isTrue();
        assertThat(wed.path("instructorVisibleAt").asText()).isEqualTo(local("2026-10-06T18:50").toString());
        assertThat(waiting.path("state").asText()).isEqualTo("WAITLISTED"); assertThat(waiting.path("id").asText()).isEqualTo(entry.path("id").asText());
        assertThat(waiting.path("endsAtLocal").isNull()).isTrue();
        assertThat(activity.path("state").asText()).isEqualTo("REGISTERED"); assertThat(activity.path("title").asText()).isEqualTo("Torneig fictici");
        assertThat(activity.path("ringName").asText()).isEqualTo("totes les pistes"); assertThat(activity.path("dogId").isNull()).isTrue();
        assertThat(all.at("/history/monthsVisible").asInt()).isEqualTo(2);
        long unread = mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("accountId").is("s08-laura").and("channel").is("APP")), "notifications");
        assertThat(unread).isPositive(); assertThat(all.at("/notifications/unreadCount").asLong()).isEqualTo(unread);
        assertThat(all.path("impersonation").isNull()).isTrue();
        // In another locale the titles come localized from the back.
        var es = mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me/home").header("Host", HOST)
                .header("Accept-Language", "es").with(as("laura"))).andReturn().getResponse().getContentAsString());
        assertThat(es.at("/reservations/0/title").asText()).isEqualTo("Entrenamiento"); assertThat(es.at("/reservations/1/title").asText()).isEqualTo("Clase Classe wed");

        // One dog: its rows and the member's activity; the counters follow the filter.
        var duna = home("laura", "s08-d-duna", 200);
        assertThat(duna.path("selectedDogId").asText()).isEqualTo("s08-d-duna");
        assertThat(duna.path("reservations")).extracting(r -> r.path("type").asText()).containsExactly("CLASS", "CLASS_WAITLIST", "ACTIVITY");
        assertThat(duna.at("/limits/currentWeek/count").asInt()).isEqualTo(1); assertThat(duna.at("/limits/nextWeek/count").asInt()).isZero();
        // A CANCELLED_LATE booking still counts, an in-time cancellation does not (R-08-02).
        clock.setInstant(local("2026-10-07T18:00"));
        cancel(as("laura"), wed.path("id").asText(), 200);
        var late = home("laura", "s08-d-duna", 200);
        assertThat(late.at("/limits/currentWeek/count").asInt()).isEqualTo(1);
        assertThat(late.path("reservations")).extracting(r -> r.path("type").asText()).containsExactly("CLASS_WAITLIST", "ACTIVITY");
        // The instructor shows inside the 24 h window.
        var rock = home("laura", "s08-d-rock", 200).at("/reservations/0");
        assertThat(rock.path("startsAtLocal").asText()).isEqualTo("2026-10-08T17:40"); assertThat(rock.path("instructorName").asText()).isEqualTo("Estela");
        assertThat(rock.path("instructorVisibleAt").isNull()).isTrue();
        clock.setInstant(NOW);

        // Past rows (endsAt ≤ now) leave the list.
        clock.setInstant(local("2026-10-07T08:30"));
        assertThat(home("laura", "s08-d-rock", 200).path("reservations")).extracting(r -> r.path("type").asText()).containsExactly("CLASS", "ACTIVITY");
        clock.setInstant(NOW);

        // Foreign dog → 404 DOG_NOT_ACCESSIBLE; a group member sees the group's dogs too.
        assertThat(code(home("laura", "s08-d-nit", 404))).isEqualTo("DOG_NOT_ACCESSIBLE");
        assertThat(home("joan", null, 200).path("dogs")).extracting(d -> d.path("name").asText()).containsExactly("Toby", "Duna", "Rock");
        assertThat(home("pere", null, 200).path("dogs")).extracting(d -> d.path("name").asText()).containsExactly("Nit");

        // Module off (§9): FREE_TRAINING, ACTIVITIES, WAITLIST and FAMILY_GROUP remove their rows and chips.
        modules(Arrays.stream(Module.values()).filter(m -> !Set.of(Module.FREE_TRAINING, Module.ACTIVITIES, Module.WAITLIST, Module.FAMILY_GROUP).contains(m))
                .toArray(Module[]::new));
        var off = home("laura", null, 200);
        assertThat(off.path("dogs")).extracting(d -> d.path("name").asText()).containsExactly("Duna", "Rock");
        assertThat(off.path("reservations")).extracting(r -> r.path("type").asText()).containsExactly("CLASS");
        assertThat(code(home("laura", "s08-d-toby", 404))).isEqualTo("DOG_NOT_ACCESSIBLE");
    }

    @Test void T_07_27_homeHasNoActivityRowsWhileActivitiesIsOffAndShowsThemAgainAfter() throws Exception {
        activity("s08-act", "2026-10-10", "s08-m-laura");
        assertThat(home("laura", null, 200).path("reservations")).extracting(r -> r.path("type").asText()).containsExactly("ACTIVITY");
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.ACTIVITIES).toArray(Module[]::new));
        assertThat(home("laura", null, 200).path("reservations")).isEmpty();
        assertThat(bookable("laura", "s08-d-duna", 200).path("activities")).isEmpty();
        modules(Module.values());
        assertThat(home("laura", null, 200).path("reservations")).extracting(r -> r.path("type").asText()).containsExactly("ACTIVITY");
    }

    @Test void T_08_12_homeUnderImpersonationShowsTheMemberAndTheActor() throws Exception {
        var impersonated = call(GET, "/me/home", null, impersonating("admin", "s08-m-laura"), 200);
        assertThat(impersonated.at("/member/id").asText()).isEqualTo("s08-m-laura");
        assertThat(impersonated.at("/impersonation/actorName").asText()).isNotBlank();
        assertThat(call(GET, "/me/bookable-classes", null, impersonating("admin", "s08-m-laura"), 200).at("/dog/id").asText()).isEqualTo("s08-d-duna");
    }

    @Test void T_08_13_bookableClassesProposesOneDogAndDecidesEveryRowState() throws Exception {
        session("beyond", "2026-10-27T18:50", 3, List.of());
        // Proposed dog: the first own dog, then lastDogForClass while it stays accessible.
        var first = bookable("laura", null, 200);
        assertThat(first.at("/dog/name").asText()).isEqualTo("Duna"); assertThat(first.at("/dog/levelName").asText()).isEqualTo("C");
        assertThat(first.at("/dog/sex").asText()).isEqualTo("FEMALE"); assertThat(first.at("/dog/own").asBoolean()).isTrue();
        assertThat(first.path("dogs")).extracting(d -> d.path("name").asText()).containsExactly("Duna", "Rock", "Toby");
        assertThat(first.path("pack").isNull()).isTrue(); assertThat(first.path("singleClass").isNull()).isTrue(); assertThat(first.path("bookingBlock").isNull()).isTrue();
        // Horizon: W0…W2 by start, the level filter drops levelD, nothing after W2; W2 is «Properament» with its opening.
        assertThat(ids(first)).containsExactly("wed", "thu", "last", "fri", "sat", "mon", "mon2", "later");
        assertThat(row(first, "wed").path("week").asText()).isEqualTo("CURRENT"); assertThat(row(first, "mon").path("week").asText()).isEqualTo("NEXT");
        var later = row(first, "later");
        assertThat(later.path("week").asText()).isEqualTo("LATER"); assertThat(later.path("state").asText()).isEqualTo("NOT_YET_OPEN");
        assertThat(later.path("opensAt").asText()).isEqualTo("2026-10-11T18:00:00Z");
        var wed = row(first, "wed");
        assertThat(wed.path("state").asText()).isEqualTo("BOOKABLE"); assertThat(wed.path("freeSeats").asInt()).isEqualTo(3);
        assertThat(wed.path("waiting").asInt()).isZero(); assertThat(wed.path("waitlistMax").asInt()).isEqualTo(3);
        assertThat(wed.path("startsAtLocal").asText()).isEqualTo("2026-10-07T18:50"); assertThat(wed.path("endsAtLocal").asText()).isEqualTo("2026-10-07T19:50");
        assertThat(wed.path("description").asText()).isEqualTo("Classe wed"); assertThat(wed.path("ringName").asText()).isEqualTo("Central");
        assertThat(wed.path("ringColor").asText()).isEqualTo("#8FCE8F"); assertThat(wed.path("price").isNull()).isTrue(); assertThat(wed.path("opensAt").isNull()).isTrue();

        // Booked and waited-for classes leave the list; a full class shows WAITLIST_OPEN.
        book(as("laura"), "levelD", "s08-d-rock");
        assertThat(bookable("laura", null, 200).at("/dog/name").asText()).as("lastDogForClass").isEqualTo("Rock");
        book(as("laura"), "wed", "s08-d-duna");
        book(as("pere"), "last", "s08-d-nit");
        cache.invalidate(CLUB);
        var duna = bookable("laura", "s08-d-duna", 200);
        assertThat(ids(duna)).doesNotContain("wed");
        assertThat(row(duna, "last").path("state").asText()).isEqualTo("WAITLIST_OPEN"); assertThat(row(duna, "last").path("freeSeats").asInt()).isZero();
        join(as("laura"), "last", "s08-d-duna", 201);
        assertThat(ids(bookable("laura", "s08-d-duna", 200))).doesNotContain("wed", "last");
        // The base cache serves the class counters for 30 s; the per-dog state is always live.
        book(as("c0"), "thu", "s08-d-c0");
        assertThat(row(bookable("laura", "s08-d-duna", 200), "thu").path("freeSeats").asInt()).as("base cached").isEqualTo(3);
        clock.advance(Duration.ofSeconds(31));
        assertThat(row(bookable("laura", "s08-d-duna", 200), "thu").path("freeSeats").asInt()).as("base reloaded").isEqualTo(2);
        clock.setInstant(NOW); cache.invalidate(CLUB);
        // WAITLIST_FULL at waitlist.maxPerClass, FULL without WAITLIST.
        join(as("c1"), "last", "s08-d-c1", 201); join(as("c2"), "last", "s08-d-c2", 201);
        cache.invalidate(CLUB);
        var toby = bookable("joan", "s08-d-toby", 200);
        assertThat(row(toby, "last").path("state").asText()).isEqualTo("WAITLIST_FULL"); assertThat(row(toby, "last").path("waiting").asInt()).isEqualTo(3);
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.WAITLIST).toArray(Module[]::new)); cache.invalidate(CLUB);
        var noWaitlist = row(bookable("joan", "s08-d-toby", 200), "last");
        assertThat(noWaitlist.path("state").asText()).isEqualTo("FULL"); assertThat(noWaitlist.path("waiting").isNull()).isTrue();
        assertThat(noWaitlist.path("waitlistMax").isNull()).isTrue();
        modules(Module.values()); cache.invalidate(CLUB);

        // R-08-03: W0 at the limit with a swappable booking stays BOOKABLE; once nothing is swappable → WEEKLY_LIMIT_DONE.
        book(as("laura"), "thu", "s08-d-duna");
        assertThat(row(bookable("laura", "s08-d-duna", 200), "fri").path("state").asText()).isEqualTo("BOOKABLE");
        clock.setInstant(local("2026-10-08T17:00")); cache.invalidate(CLUB); // wed done, thu inside the 4 h threshold
        var limit = bookable("laura", "s08-d-duna", 200);
        assertThat(row(limit, "fri").path("state").asText()).isEqualTo("WEEKLY_LIMIT_DONE"); assertThat(row(limit, "sat").path("state").asText()).isEqualTo("WEEKLY_LIMIT_DONE");
        assertThat(row(limit, "mon").path("state").asText()).as("W1 has its own limit").isEqualTo("BOOKABLE");
        clock.setInstant(local("2026-10-09T20:30")); cache.invalidate(CLUB);
        assertThat(ids(bookable("laura", "s08-d-duna", 200))).as("started classes leave the list").doesNotContain("fri").contains("sat");
        clock.setInstant(NOW); cache.invalidate(CLUB);

        // PACK_EMPTY and the pack card (PACKS): Toby's pack is used up; Duna's is expiring; module off → no card.
        openPack("s08-m-joan", "s08-d-toby", 10, 10, LocalDate.parse("2026-12-31"));
        openPack("s08-m-laura", "s08-d-duna", 10, 6, LocalDate.parse("2026-10-09"));
        mongo.save(new Document("_id", "s08-pack-plan").append("clubId", CLUB).append("type", "PACK")
                .append("name", new Document("values", new Document("ca", "Pack 10").append("es", "Bono 10")).append("defaultLocale", "ca")), "plans");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-joan")), new Update().set("planId", "s08-pack-plan"), "members");
        var empty = bookable("joan", "s08-d-toby", 200);
        assertThat(empty.at("/pack/state").asText()).isEqualTo("EMPTY"); assertThat(empty.at("/pack/planName").asText()).isEqualTo("Pack 10");
        assertThat(empty.at("/pack/sessionsTotal").asInt()).isEqualTo(10); assertThat(empty.at("/pack/available").asInt()).isZero();
        assertThat(row(empty, "fri").path("state").asText()).isEqualTo("PACK_EMPTY");
        assertThat(row(empty, "later").path("state").asText()).as("NOT_YET_OPEN wins over PACK_EMPTY").isEqualTo("NOT_YET_OPEN");
        var expiring = bookable("laura", "s08-d-duna", 200);
        assertThat(expiring.at("/pack/state").asText()).isEqualTo("EXPIRING"); assertThat(expiring.at("/pack/consumed").asInt()).isEqualTo(6);
        assertThat(expiring.at("/pack/expiresOn").asText()).isEqualTo("2026-10-09");
        assertThat(row(expiring, "fri").path("state").asText()).as("class on the expiry date").isEqualTo("BOOKABLE");
        assertThat(row(expiring, "sat").path("state").asText()).as("class after the expiry date").isEqualTo("PACK_EMPTY");
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.PACKS).toArray(Module[]::new));
        assertThat(bookable("joan", "s08-d-toby", 200).path("pack").isNull()).isTrue();
        assertThat(row(bookable("joan", "s08-d-toby", 200), "fri").path("state").asText()).isEqualTo("BOOKABLE");
        modules(Module.values()); packs.clear();

        // SINGLE_CLASS: the terms and each row's price; module off → none.
        mongo.save(new Document("_id", "s08-plan").append("clubId", CLUB).append("type", "SINGLE_CLASS").append("singleClass", new Document("chargeMode", "CHARGE_ON_ATTENDANCE")), "plans");
        mongo.save(new Document("_id", "s08-price").append("clubId", CLUB).append("planId", "s08-plan").append("amount", new Document("amountMinor", 1200L).append("currency", "EUR")), "prices");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-pere")), new Update().set("planId", "s08-plan").set("priceId", "s08-price"), "members");
        var single = bookable("pere", null, 200);
        assertThat(single.at("/singleClass/chargeMode").asText()).isEqualTo("CHARGE_ON_ATTENDANCE"); assertThat(single.at("/singleClass/pricePerClass/amountMinor").asLong()).isEqualTo(1200);
        assertThat(row(single, "fri").at("/price/amountMinor").asLong()).isEqualTo(1200); assertThat(row(single, "fri").at("/price/currency").asText()).isEqualTo("EUR");
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.SINGLE_CLASS).toArray(Module[]::new));
        var noSingle = bookable("pere", null, 200);
        assertThat(noSingle.path("singleClass").isNull()).isTrue(); assertThat(row(noSingle, "fri").path("price").isNull()).isTrue();
        modules(Module.values());

        // NOT_BOOKABLE: leave date (LEAVING from the end of that local day), inactivity (INACTIVITY), booking block (BLOCKED + banner).
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-pere")), new Update().set("leaveDate", "2026-10-09"), "members");
        var leaving = bookable("pere", null, 200);
        assertThat(row(leaving, "fri").path("state").asText()).as(leaving.toString()).isEqualTo("BOOKABLE");
        assertThat(row(leaving, "sat").path("state").asText()).isEqualTo("NOT_BOOKABLE"); assertThat(row(leaving, "sat").path("notBookableReason").asText()).isEqualTo("LEAVING");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-pere")), new Update().unset("leaveDate"), "members");
        try (var tenant = TenantContext.open(CLUB)) { inactivity.approve("s08-m-pere", LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-31")); }
        var inactive = bookable("pere", null, 200);
        assertThat(row(inactive, "fri").path("state").asText()).isEqualTo("BOOKABLE");
        assertThat(row(inactive, "mon").path("notBookableReason").asText()).isEqualTo("INACTIVITY");
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.INACTIVITY).toArray(Module[]::new));
        assertThat(row(bookable("pere", null, 200), "mon").path("state").asText()).isEqualTo("BOOKABLE");
        modules(Module.values());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-joan")), new Update().set("bookingBlock", new Document("active", true).append("reason", "Quota pendent")), "members");
        var blocked = bookable("laura", "s08-d-toby", 200);
        assertThat(blocked.at("/bookingBlock/reason").asText()).as("the owner's block").isEqualTo("Quota pendent");
        assertThat(blocked.path("classes")).allSatisfy(c -> assertThat(c.path("notBookableReason").asText()).isEqualTo("BLOCKED"));
        assertThat(bookable("laura", "s08-d-duna", 200).path("bookingBlock").isNull()).isTrue();

        // A dog that left is no longer proposed nor accessible: the first own dog is.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-d-rock")), new Update().set("status", "LEFT"), "dogs");
        assertThat(bookable("laura", null, 200).at("/dog/name").asText()).isEqualTo("Duna");
        assertThat(code(bookable("laura", "s08-d-rock", 404))).isEqualTo("DOG_NOT_ACCESSIBLE");
    }

    @Test void T_08_13_bookableClassesListsTheActivitiesAdmittedForTheSelectedDog() throws Exception {
        activity("s08-open", "2026-10-10", null);
        var list = bookable("laura", "s08-d-duna", 200);
        assertThat(list.path("activities")).extracting(a -> a.path("title").asText() + ":" + a.path("startsAtLocal").asText() + ":" + a.path("freeSeats").asInt())
                .containsExactly("Torneig fictici:2026-10-10T18:30:20");
        activity("s08-mine", "2026-10-11", "s08-m-laura");
        assertThat(bookable("laura", "s08-d-duna", 200).path("activities")).extracting(a -> a.path("id").asText()).containsExactly("s08-open");
    }

    @Test void T_08_26_aggregatesAreMemberOnlyAndTenantScoped() throws Exception {
        assertThat(code(bookable("pere", "s08-d-duna", 404))).isEqualTo("DOG_NOT_ACCESSIBLE");
        assertThat(bookable("joan", "s08-d-duna", 200).at("/dog/own").asBoolean()).as("family group").isFalse();
        for (String staff : List.of("admin", "inst")) {
            call(GET, "/me/home", null, as(staff), 403); call(GET, "/me/bookable-classes", null, as(staff), 403);
        }
        call(GET, "/me/home", null, null, 401); call(GET, "/me/bookable-classes", null, null, 401);
        // A member of club B reaches neither club A's dogs nor its classes.
        mongo.save(new com.agilityhub.core.identity.persistence.Account("s08-b", "s08-b@example.test", "Example b", "ca", null, Set.of(),
                com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
        mongo.save(new com.agilityhub.core.identity.persistence.Membership("s08-b", "s08-b", OTHER, "s08-b-m", Set.of(com.agilityhub.core.identity.domain.Role.MEMBER),
                com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, com.agilityhub.core.identity.domain.Role.MEMBER));
        mongo.save(new Document("_id", "s08-b-m").append("clubId", OTHER).append("accountId", "s08-b").append("firstName", "Berta").append("status", "ACTIVE")
                .append("bookingBlock", new Document("active", false)).append("version", 0), "members");
        mongo.save(new Document("_id", "s08-b-d").append("clubId", OTHER).append("memberId", "s08-b-m").append("name", "Brisa").append("sex", "FEMALE")
                .append("status", "ACTIVE").append("version", 0), "dogs");
        var other = jwt().jwt(j -> j.subject("s08-b").claim("clubId", OTHER).claim("memberId", "s08-b-m")).authorities(() -> "ROLE_MEMBER");
        var mvcResult = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me/bookable-classes?dogId=s08-d-duna")
                .header("Host", OTHER_HOST).with(other)).andReturn().getResponse();
        assertThat(mvcResult.getStatus()).isEqualTo(404);
        var otherList = mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me/bookable-classes")
                .header("Host", OTHER_HOST).with(other)).andReturn().getResponse().getContentAsString());
        assertThat(otherList.at("/dog/name").asText()).isEqualTo("Brisa"); assertThat(otherList.path("classes")).isEmpty();
        var otherHome = mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me/home")
                .header("Host", OTHER_HOST).with(other)).andReturn().getResponse().getContentAsString());
        assertThat(otherHome.path("dogs")).extracting(d -> d.path("name").asText()).containsExactly("Brisa"); assertThat(otherHome.path("reservations")).isEmpty();
        // Tenant mismatch: club A's token on club B's host.
        var mismatch = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me/home").header("Host", OTHER_HOST).with(as("laura")))
                .andReturn().getResponse();
        assertThat(mismatch.getStatus()).isEqualTo(403);
        // A member without any dog has nothing to propose on 04.
        mongo.remove(Query.query(Criteria.where("_id").is("s08-b-d")), "dogs");
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me/bookable-classes")
                .header("Host", OTHER_HOST).with(other)).andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}
