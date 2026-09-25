package com.agilityhub.core.clubs.training.api;

import com.agilityhub.core.clubs.dashboard.application.ports.TrainingBookingsQuery;
import com.agilityhub.core.clubs.scheduling.application.ports.TrainingConflictPort;
import com.agilityhub.core.clubs.scheduling.application.ports.TrainingOccupancyPort;
import com.agilityhub.core.clubs.training.application.TrainingBookingService;
import com.agilityhub.core.clubs.training.application.TrainingEligibilityService;
import com.agilityhub.core.clubs.training.domain.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;

/** S09 WP-09-B/C over real Mongo: grid, eligibility, booking, cancellation, occupancy, conflicts, cache, notifications, roles and modules. */
class TrainingIT extends TrainingFixtures {
    @Autowired TrainingOccupancyPort occupancy; @Autowired TrainingConflictPort conflicts; @Autowired TrainingBookingsQuery dashboard;
    @Autowired TrainingBookingService service; @Autowired TrainingEligibilityService eligibility;

    @Test void T_09_02_eligibleDogsFollowTheFamilyGroupAndTheLevels() throws Exception {
        var summary = call(GET, "/me/training-summary", null, as("maria"), 200);
        var dogs = summary.path("eligibleDogs");
        assertThat(dogs).extracting(d -> d.path("id").asText()).containsExactly("s09-d-rock", "s09-d-kira");
        assertThat(dogs.get(0).path("rightSource").asText()).isEqualTo("LEVEL"); assertThat(dogs.get(0).path("levelName").asText()).isEqualTo("D");
        assertThat(dogs.get(0).path("ownerName").isNull()).isTrue();
        assertThat(dogs.get(1).path("rightSource").asText()).isEqualTo("MANUAL"); assertThat(dogs.get(1).path("ownerName").asText()).isEqualTo("Joan");
        assertThat(summary.path("defaultDogId").asText()).isEqualTo("s09-d-rock"); assertThat(summary.path("limitUnit").asText()).isEqualTo("DOG");
        assertThat(summary.at("/weekOpensAt/dayOfWeek").asText()).isEqualTo("SUNDAY"); assertThat(summary.at("/weekOpensAt/time").asText()).isEqualTo("20:00");
        assertThat(call(GET, "/me/training-summary", null, as("joan"), 200).path("eligibleDogs")).extracting(d -> d.path("id").asText()).containsExactly("s09-d-kira", "s09-d-rock");
        // A member of the group who is no longer ACTIVE does not lend their dogs.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s09-m-joan")), new Update().set("status", "LEFT"), "members");
        assertThat(call(GET, "/me/training-summary", null, as("maria"), 200).path("eligibleDogs")).extracting(d -> d.path("id").asText()).containsExactly("s09-d-rock");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s09-m-joan")), new Update().set("status", "ACTIVE"), "members");
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.FAMILY_GROUP).toArray(Module[]::new));
        assertThat(call(GET, "/me/training-summary", null, as("maria"), 200).path("eligibleDogs")).extracting(d -> d.path("id").asText()).containsExactly("s09-d-rock");
        assertThat(code(call(GET, "/me/training-summary?dogId=s09-d-kira", null, as("maria"), 404))).isEqualTo("DOG_NOT_ACCESSIBLE");
        modules(Module.values()); parameter("levels.enabled", false);
        var withoutLevels = call(GET, "/me/training-summary", null, as("maria"), 200).path("eligibleDogs");
        assertThat(withoutLevels).extracting(d -> d.path("id").asText()).containsExactly("s09-d-kira");
        assertThat(withoutLevels.get(0).path("levelName").isNull()).isTrue();
        try (var tenant = TenantContext.open(CLUB)) {
            parameter("levels.enabled", true);
            assertThat(eligibility.membersWithRight()).contains("s09-m-maria", "s09-m-joan", "s09-m-pau", "s09-m-sergio").doesNotContain("s09-m-admin");
        }
    }

    @Test void T_09_03_T_09_04_T_09_22_theGridListsReservableRingsDaysAndMountedSetups() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CEN)), new Update().set("trainingCapacity", 2), "rings");
        var grid = slots(as("maria"), "2026-10-05", "2026-10-12", "s09-d-rock");
        assertThat(grid.path("timeZone").asText()).isEqualTo("Europe/Madrid"); assertThat(grid.path("slotMinutes").asInt()).isEqualTo(30);
        assertThat(grid.at("/window/from").asText()).isEqualTo("2026-10-05"); assertThat(grid.at("/window/to").asText()).isEqualTo("2026-10-08");
        assertThat(grid.path("dogEligible").asBoolean()).isTrue();
        assertThat(grid.path("rings")).extracting(r -> r.path("id").asText()).containsExactly(MUN, CEN, CAR, CAD);
        assertThat(grid.path("rings")).extracting(r -> r.path("capacity").asInt()).containsExactly(1, 2, 1, 1);
        assertThat(grid.path("days")).extracting(d -> d.path("date").asText()).containsExactly("2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08");
        var monday = grid.path("days").get(0).path("slots");
        assertThat(monday).hasSize(30); assertThat(monday.get(0).path("startsAt").asText()).isEqualTo("2026-10-05T05:00:00Z");
        assertThat(monday.get(0).path("startsAtLocal").asText()).isEqualTo("07:00"); assertThat(monday.get(0).path("endsAtLocal").asText()).isEqualTo("07:30");
        assertThat(monday.get(0).path("bookable").asBoolean()).as("07:00 already started at 08:00").isFalse();
        assertThat(slot(grid, "2026-10-05T08:30").path("bookable").asBoolean()).isTrue();
        assertThat(cell(grid, "2026-10-05T08:30", MUN).path("state").asText()).isEqualTo("FREE");
        assertThat(cell(grid, "2026-10-05T08:30", MUN).path("occupants").isMissingNode() || cell(grid, "2026-10-05T08:30", MUN).path("occupants").isNull()).isTrue();
        assertThat(slots(as("maria"), "2026-10-05", "2026-10-06", "s09-d-toby").path("dogEligible").asBoolean()).isFalse();
        // Staff: the holiday is closed; more than 31 days is refused; an inaccessible dog is 404 for a member.
        var holiday = slots(as("estel"), "2026-10-12", "2026-10-12", null).path("days").get(0);
        assertThat(holiday.path("closed").asBoolean()).isTrue(); assertThat(holiday.path("slots")).isEmpty();
        assertThat(code(call(GET, "/training-slots?from=2026-10-01&to=2026-11-15", null, as("admin"), 400))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(call(GET, "/training-slots?from=2026-10-06&to=2026-10-05", null, as("maria"), 400))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(call(GET, "/training-slots?from=2026-10-05&to=2026-10-05&dogId=s09-d-thai", null, as("maria"), 404))).isEqualTo("DOG_NOT_ACCESSIBLE");
        assertThat(call(GET, "/training-slots?from=2026-10-05&to=2026-10-05&ringId=" + CEN, null, as("maria"), 200).path("rings")).hasSize(1);
        call(GET, "/training-slots?from=2026-10-05&to=2026-10-05&ringId=" + PET, null, as("maria"), 404);
        // R-09-15: COURSES on and a mounted setup on Muntanya.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(MUN)), new Update().set("activeSetupId", "s09-setup"), "rings");
        setups.setups.put("s09-setup", new com.agilityhub.core.clubs.training.application.ports.RingSetupPort.Setup("s09-setup", "AGILITY", List.of("D"),
                Instant.parse("2026-10-04T16:10:00Z"), LocalDate.parse("2026-10-11")));
        assertThat(slots(as("maria"), "2026-10-05", "2026-10-05", null).at("/rings/0/setup/id").asText()).isEqualTo("s09-setup");
        assertThat(slots(as("maria"), "2026-10-05", "2026-10-05", null).at("/rings/0/setup/levelNames/0").asText()).isEqualTo("D");
        parameter("courses.showSetupToMembers", false);
        assertThat(slots(as("maria"), "2026-10-05", "2026-10-05", null).at("/rings/0/setup").isNull()).isTrue();
        assertThat(slots(as("estel"), "2026-10-05", "2026-10-05", null).at("/rings/0/setup/kind").asText()).isEqualTo("AGILITY");
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.COURSES).toArray(Module[]::new));
        assertThat(slots(as("estel"), "2026-10-05", "2026-10-05", null).at("/rings/0/setup").isNull()).isTrue();
    }

    @Test void T_09_06_T_09_27_classesBlocksAndBookingsShowInTheGridRedactedForMembers() throws Exception {
        classSession("s09-draft", MUN, "2026-10-06T08:30", "2026-10-06T09:40", "DRAFT");
        block("s09-block", CAR, "2026-10-06T16:00", "2026-10-06T18:00", "BLOCK", "MAINTENANCE", "Reg de la pista", null);
        var rock = book(as("maria"), "s09-d-rock", "2026-10-06T09:30", CEN, 201);
        var grid = slots(as("maria"), "2026-10-06", "2026-10-06", "s09-d-rock");
        for (String time : List.of("08:30", "09:00", "09:30")) {
            assertThat(cell(grid, "2026-10-06T" + time, MUN).path("state").asText()).isEqualTo("BLOCKED");
            assertThat(cell(grid, "2026-10-06T" + time, MUN).path("reason").asText()).isEqualTo("CLASS");
        }
        for (String time : List.of("16:00", "16:30", "17:00", "17:30")) { assertThat(cell(grid, "2026-10-06T" + time, CAR).path("reason").asText()).isEqualTo("RING_BLOCK"); }
        var own = cell(grid, "2026-10-06T09:30", CEN);
        assertThat(own.path("state").asText()).isEqualTo("BOOKED"); assertThat(own.path("reason").asText()).isEqualTo("OWN_TRAINING");
        assertThat(own.path("bookingId").asText()).isEqualTo(rock.path("id").asText());
        assertThat(own.path("occupants").isNull() && own.path("block").isNull() && own.path("classSession").isNull()).isTrue();
        assertThat(cell(slots(as("pau"), "2026-10-06", "2026-10-06", "s09-d-blat"), "2026-10-06T09:30", CEN).path("reason").asText()).isEqualTo("TRAINING");
        assertThat(slot(grid, "2026-10-06T09:30").path("anyFree").asBoolean()).isTrue();
        // T-09-27: the staff grid names the occupants, the block (with its note) and the class.
        var staff = slots(as("estel"), "2026-10-06", "2026-10-06", null);
        assertThat(cell(staff, "2026-10-06T09:30", CEN).at("/occupants/0/memberName").asText()).isEqualTo("Maria");
        assertThat(cell(staff, "2026-10-06T09:30", CEN).at("/occupants/0/dogName").asText()).isEqualTo("Rock");
        assertThat(cell(staff, "2026-10-06T16:00", CAR).at("/block/note").asText()).isEqualTo("Reg de la pista");
        assertThat(cell(staff, "2026-10-06T16:00", CAR).at("/block/kind").asText()).isEqualTo("BLOCK");
        assertThat(cell(staff, "2026-10-06T08:30", MUN).at("/classSession/id").asText()).isEqualTo("s09-draft");
        // GET /ring-blocks: a member never sees the note nor the author.
        var memberBlocks = call(GET, "/ring-blocks?filter=ringId:eq:" + CAR, null, as("maria"), 200).path("items");
        assertThat(memberBlocks).hasSize(1); assertThat(memberBlocks.get(0).path("note").isNull() || memberBlocks.get(0).path("note").isMissingNode()).isTrue();
        assertThat(memberBlocks.get(0).path("createdByName").isNull() || memberBlocks.get(0).path("createdByName").isMissingNode()).isTrue();
        assertThat(call(GET, "/ring-blocks?filter=ringId:eq:" + CAR, null, as("estel"), 200).at("/items/0/note").asText()).isEqualTo("Reg de la pista");
    }

    @Test void T_09_07_T_09_14_T_09_15_theWindowTheGridAndStartedSlotsAreEnforcedOnBooking() throws Exception {
        clock.setInstant(local("2026-10-05T23:59"));
        var out = book(as("maria"), "s09-d-rock", "2026-10-09T07:00", MUN, 422);
        assertThat(code(out)).isEqualTo("SLOT_OUT_OF_WINDOW"); assertThat(out.at("/details/from").asText()).isEqualTo("2026-10-05"); assertThat(out.at("/details/to").asText()).isEqualTo("2026-10-08");
        clock.setInstant(local("2026-10-06T00:00"));
        book(as("maria"), "s09-d-rock", "2026-10-09T07:00", MUN, 201);
        clock.setInstant(NOW);
        assertThat(code(book(as("pau"), "s09-d-blat", "2026-10-05T08:20", MUN, 400))).isEqualTo("SLOT_NOT_ON_GRID");
        assertThat(code(book(as("pau"), "s09-d-blat", "2026-10-05T06:30", MUN, 422))).isEqualTo("CLUB_CLOSED");
        assertThat(code(book(as("pau"), "s09-d-blat", "2026-10-12T09:00", MUN, 422))).isEqualTo("CLUB_CLOSED");
        clock.setInstant(local("2026-10-05T08:31"));
        assertThat(code(book(as("pau"), "s09-d-blat", "2026-10-05T08:30", MUN, 422))).isEqualTo("SLOT_OUT_OF_WINDOW");
        book(as("pau"), "s09-d-blat", "2026-10-05T09:00", MUN, 201);
        assertThat(code(book(as("pau"), "s09-d-blat", "2026-10-05T10:00", PET, 422))).isEqualTo("RING_NOT_RESERVABLE");
        assertThat(code(book(as("pau"), "s09-d-blat", "2026-10-05T10:00", "s09-r-missing", 404))).isEqualTo("NOT_FOUND");
        assertThat(code(book(as("maria"), "s09-d-toby", "2026-10-05T10:00", MUN, 422))).isEqualTo("DOG_NOT_ALLOWED");
    }

    @Test void T_09_08_T_09_35_theSummaryCountsTheSessionWeekAndMovesAtTheOpening() throws Exception {
        var tue = book(as("maria"), "s09-d-rock", "2026-10-06T07:30", MUN, 201);
        book(as("maria"), "s09-d-rock", "2026-10-08T20:30", MUN, 201);
        var wed = book(as("maria"), "s09-d-rock", "2026-10-07T10:00", MUN, 201);
        assertThat(wed.at("/counter/used").asInt()).isEqualTo(3); assertThat(wed.at("/counter/remaining").asInt()).isZero();
        cancel(as("maria"), wed.path("id").asText(), null, 200);
        clock.setInstant(local("2026-10-07T12:00")); // Tuesday's session is done: it still counts
        var summary = call(GET, "/me/training-summary?dogId=s09-d-rock&date=2026-10-06", null, as("maria"), 200);
        assertThat(summary.at("/counter/used").asInt()).isEqualTo(2); assertThat(summary.at("/counter/limit").asInt()).isEqualTo(3);
        assertThat(summary.at("/counter/remaining").asInt()).isEqualTo(1);
        assertThat(summary.at("/week/start").asText()).isEqualTo("2026-10-04T18:00:00Z"); assertThat(summary.at("/week/end").asText()).isEqualTo("2026-10-11T18:00:00Z");
        assertThat(summary.at("/counter/resetsAt").asText()).isEqualTo("2026-10-11T18:00:00Z"); assertThat(summary.at("/week/current").asBoolean()).isTrue();
        assertThat(summary.path("cancellableBookings")).extracting(b -> b.path("ringName").asText()).containsExactly("Muntanya");
        assertThat(training(tue.path("id").asText()).getDate("weekStart").toInstant()).isEqualTo(Instant.parse("2026-10-04T18:00:00Z"));
        assertThat(call(GET, "/me/training-summary?dogId=s09-d-rock&date=2026-10-13", null, as("maria"), 200).at("/counter/used").asInt()).isZero();
        // Friday books Sunday 20:30: the session belongs to the next week (counted by session date).
        clock.setInstant(local("2026-10-09T10:00"));
        var sunday = book(as("maria"), "s09-d-rock", "2026-10-11T20:30", MUN, 201);
        assertThat(sunday.at("/counter/used").asInt()).isEqualTo(1);
        assertThat(call(GET, "/me/training-summary?dogId=s09-d-rock&date=2026-10-13", null, as("maria"), 200).at("/counter/used").asInt()).isEqualTo(1);
        // T-09-35: the current week of the summary (no date) moves at Sunday 20:00 local; nothing is reset.
        clock.setInstant(local("2026-10-11T19:59"));
        var before = call(GET, "/me/training-summary?dogId=s09-d-rock", null, as("maria"), 200);
        assertThat(before.at("/week/start").asText()).isEqualTo("2026-10-04T18:00:00Z"); assertThat(before.at("/counter/used").asInt()).isEqualTo(2);
        clock.setInstant(local("2026-10-11T20:00"));
        var after = call(GET, "/me/training-summary?dogId=s09-d-rock", null, as("maria"), 200);
        assertThat(after.at("/week/start").asText()).isEqualTo("2026-10-11T18:00:00Z"); assertThat(after.at("/counter/used").asInt()).isEqualTo(1);
        publish(new com.agilityhub.core.support.TestEvent("TrainingCounterReset", null, CLUB, "Club", CLUB, clock.instant(), Map.of("weekStart", "2026-10-11T18:00:00Z"), null, null,
                com.agilityhub.core.shared.domain.DomainEvent.Origin.SYSTEM));
        dispatch();
        assertThat(call(GET, "/me/training-summary?dogId=s09-d-rock", null, as("maria"), 200).at("/counter/used").asInt()).as("nothing to reset").isEqualTo(1);
        assertThat(count("training_bookings", Criteria.where("state").is("ACTIVE"))).isEqualTo(3);
    }

    @Test void T_09_10_withTheMemberUnitTheWholeFamilyCountsAndTheLimitListsCancellableOnes() throws Exception {
        parameter("bookings.limitUnit", "MEMBER");
        book(as("maria"), "s09-d-rock", "2026-10-06T08:30", MUN, 201);
        book(as("maria"), "s09-d-rock", "2026-10-07T08:30", MUN, 201);
        var kira = book(as("maria"), "s09-d-kira", "2026-10-08T08:30", MUN, 201);
        assertThat(kira.at("/counter/used").asInt()).isEqualTo(3);
        clock.setInstant(local("2026-10-06T07:00")); // Tuesday 08:30 is inside the 120 min threshold now
        var limit = book(as("maria"), "s09-d-rock", "2026-10-08T10:00", MUN, 409);
        assertThat(code(limit)).isEqualTo("TRAINING_LIMIT_REACHED");
        assertThat(limit.at("/details/limit").asInt()).isEqualTo(3); assertThat(limit.at("/details/used").asInt()).isEqualTo(3);
        assertThat(limit.at("/details/weekStart").asText()).isEqualTo("2026-10-04T18:00:00Z"); assertThat(limit.at("/details/weekEnd").asText()).isEqualTo("2026-10-11T18:00:00Z");
        assertThat(limit.at("/details/cancellableBookings")).extracting(b -> b.path("startsAt").asText())
                .containsExactly(local("2026-10-07T08:30").toString(), local("2026-10-08T08:30").toString());
        var summary = call(GET, "/me/training-summary?date=2026-10-06", null, as("maria"), 200);
        assertThat(summary.path("limitUnit").asText()).isEqualTo("MEMBER"); assertThat(summary.at("/counter/used").asInt()).isEqualTo(3);
        assertThat(mongo.findById("s09-m-maria", Document.class, "members").getInteger("trainingSeq")).as("the refused attempt rolled its $inc back").isEqualTo(3);
        // Joan's own count is separate even though Kira is his dog.
        assertThat(call(GET, "/me/training-summary?date=2026-10-06", null, as("joan"), 200).at("/counter/used").asInt()).isZero();
    }

    @Test void T_09_11_anyRingIsAssignedInCatalogOrderAndSlotTakenListsFreeRings() throws Exception {
        assertThat(book(as("pau"), "s09-d-blat", "2026-10-05T10:00", null, 201).path("ringId").asText()).isEqualTo(MUN);
        var taken = book(as("julia"), "s09-d-lluna", "2026-10-05T10:00", MUN, 409);
        assertThat(code(taken)).isEqualTo("SLOT_TAKEN"); assertThat(taken.at("/details/ringId").asText()).isEqualTo(MUN);
        assertThat(taken.at("/details/reason").asText()).isEqualTo("TRAINING");
        assertThat(taken.at("/details/freeRings")).extracting(JsonNode::asText).containsExactly(CEN, CAR, CAD);
        assertThat(book(as("julia"), "s09-d-lluna", "2026-10-05T10:00", null, 201).path("ringId").asText()).isEqualTo(CEN);
        assertThat(book(as("sergio"), "s09-d-thai", "2026-10-05T10:00", null, 201).path("ringId").asText()).isEqualTo(CAR);
        assertThat(book(as("c0"), "s09-d-c0", "2026-10-05T10:00", null, 201).path("ringId").asText()).isEqualTo(CAD);
        var none = book(as("c1"), "s09-d-c1", "2026-10-05T10:00", null, 409);
        assertThat(none.at("/details/freeRings")).isEmpty(); assertThat(none.at("/details/ringId").isMissingNode()).isTrue();
        classSession("s09-class", MUN, "2026-10-05T11:00", "2026-10-05T12:00", "ACTIVE");
        assertThat(book(as("c1"), "s09-d-c1", "2026-10-05T11:00", MUN, 409).at("/details/reason").asText()).isEqualTo("CLASS");
        assertThat(book(as("c1"), "s09-d-c1", "2026-10-05T11:00", null, 201).path("ringId").asText()).isEqualTo(CEN);
        assertThat(slot(slots(as("c2"), "2026-10-05", "2026-10-05", null), "2026-10-05T10:00").path("anyFree").asBoolean()).isFalse();
    }

    @Test void T_09_12_T_09_25_cancellationFollowsTheThresholdFreesTheSeatAndIsIdempotent() throws Exception {
        clock.setInstant(local("2026-10-24T10:00"));
        var first = book(as("maria"), "s09-d-rock", "2026-10-25T08:00", MUN, 201);
        assertThat(first.path("cancellableUntil").asText()).isEqualTo("2026-10-25T05:00:00Z");
        clock.setInstant(local("2026-10-25T05:59"));
        var cancelled = cancel(as("maria"), first.path("id").asText(), null, 200);
        assertThat(cancelled.path("state").asText()).isEqualTo("CANCELLED"); assertThat(cancelled.path("cancelReason").asText()).isEqualTo("MEMBER_REQUEST");
        assertThat(cancelled.path("cancelledBy").asText()).isEqualTo("MEMBER"); assertThat(cancelled.at("/counter/used").asInt()).isZero();
        assertThat(code(cancel(as("maria"), first.path("id").asText(), null, 409))).isEqualTo("INVALID_STATE");
        assertThat(cell(slots(as("pau"), "2026-10-25", "2026-10-25", null), "2026-10-25T08:00", MUN).path("state").asText()).isEqualTo("FREE");
        var second = book(as("maria"), "s09-d-rock", "2026-10-25T08:00", MUN, 201);
        clock.setInstant(local("2026-10-25T06:01"));
        var late = cancel(as("maria"), second.path("id").asText(), null, 422); // catalog rule 0: 422 (S09 writes 409)
        assertThat(code(late)).isEqualTo("TRAINING_CANCEL_TOO_LATE");
        assertThat(late.at("/details/thresholdMinutes").asInt()).isEqualTo(120); assertThat(late.at("/details/minutesBefore").asInt()).isEqualTo(119);
        // T-09-25: the same Idempotency-Key replays the same 200 and changes nothing more.
        clock.setInstant(local("2026-10-24T10:00"));
        var third = book(as("pau"), "s09-d-blat", "2026-10-25T10:00", MUN, 201); String key = UUID.randomUUID().toString();
        var one = call(POST, "/training-bookings/" + third.path("id").asText() + "/cancellation", Map.of(), as("pau"), 200, key);
        var two = call(POST, "/training-bookings/" + third.path("id").asText() + "/cancellation", Map.of(), as("pau"), 200, key);
        assertThat(two).isEqualTo(one);
        assertThat(eventsOf("TrainingCancelled")).hasSize(2);
        assertThat(eventsOf("TrainingCancelled").getFirst().get("payload", Document.class)).containsEntry("by", "MEMBER").containsEntry("cancelReason", "MEMBER_REQUEST")
                .containsEntry("late", false).containsEntry("origin", "APP").containsKeys("trainingBookingId", "slotId", "ringId", "memberId", "dogId");
        clock.setInstant(local("2026-10-25T07:00")); // the outbox delivers events up to now: move past Maria's cancellation
        dispatch();
        assertThat(notifications("N-07")).extracting(n -> n.getString("channel") + ":" + n.getString("accountId")).containsExactlyInAnyOrder("APP:s09-maria", "APP:s09-pau");
    }

    @Test void T_09_16_theHappyPathBooksRemembersTheDogPublishesAndNotifiesOnce() throws Exception {
        String key = UUID.randomUUID().toString(); var body = Map.of("dogId", "s09-d-rock", "startsAt", local("2026-10-05T08:30").toString(), "ringId", MUN);
        var memberVersion = mongo.findById("s09-m-maria", Document.class, "members").get("version");
        var booked = call(POST, "/training-bookings", body, as("maria"), 201, key);
        assertThat(booked.path("state").asText()).isEqualTo("ACTIVE"); assertThat(booked.path("origin").asText()).isEqualTo("APP");
        assertThat(booked.path("slotId").asText()).isEqualTo(MUN + "_2026-10-05T06:30:00Z"); assertThat(booked.path("ringName").asText()).isEqualTo("Muntanya");
        assertThat(booked.path("dogName").asText()).isEqualTo("Rock"); assertThat(booked.path("memberId").asText()).isEqualTo("s09-m-maria");
        assertThat(booked.path("startsAtLocal").asText()).isEqualTo("08:30"); assertThat(booked.path("endsAtLocal").asText()).isEqualTo("09:00");
        assertThat(booked.path("date").asText()).isEqualTo("2026-10-05"); assertThat(booked.path("endsAt").asText()).isEqualTo("2026-10-05T07:00:00Z");
        assertThat(booked.path("cancellableUntil").asText()).isEqualTo("2026-10-05T04:30:00Z"); assertThat(booked.path("impersonation").isNull()).isTrue();
        assertThat(booked.at("/counter/used").asInt()).isEqualTo(1); assertThat(booked.at("/counter/limit").asInt()).isEqualTo(3);
        assertThat(call(POST, "/training-bookings", body, as("maria"), 201, key)).isEqualTo(booked);
        assertThat(count("training_bookings", Criteria.where("dogId").is("s09-d-rock"))).isEqualTo(1);
        var stored = training(booked.path("id").asText());
        assertThat(stored.getInteger("seatIndex")).isZero(); assertThat(stored.getString("idempotencyKey")).isEqualTo(key);
        assertThat(mongo.findById("s09-m-maria", Document.class, "members").getString("lastDogForTraining")).isEqualTo("s09-d-rock");
        assertThat(mongo.findById("s09-d-rock", Document.class, "dogs").getInteger("trainingSeq")).isEqualTo(1);
        assertThat(eventsOf("TrainingBooked")).hasSize(1);
        assertThat(eventsOf("TrainingBooked").getFirst().get("payload", Document.class)).containsEntry("trainingBookingId", booked.path("id").asText())
                .containsEntry("slotId", booked.path("slotId").asText()).containsEntry("ringId", MUN).containsEntry("memberId", "s09-m-maria")
                .containsEntry("dogId", "s09-d-rock").containsEntry("origin", "APP");
        assertThat(call(GET, "/training-bookings/" + booked.path("id").asText(), null, as("maria"), 200).path("id").asText()).isEqualTo(booked.path("id").asText());
        assertThat(call(GET, "/training-bookings/" + booked.path("id").asText(), null, as("joan"), 200).path("dogName").asText()).as("family group").isEqualTo("Rock");
        call(GET, "/training-bookings/" + booked.path("id").asText(), null, as("pau"), 404);
        assertThat(call(GET, "/me/training-bookings", null, as("maria"), 200).path("items")).extracting(b -> b.path("cancellableUntil").asText()).containsExactly("2026-10-05T04:30:00Z");
        assertThat(call(GET, "/me/training-bookings?state=CANCELLED", null, as("maria"), 200).path("items")).isEmpty();
        dispatch(); dispatch();
        assertThat(notifications("N-06")).extracting(n -> n.getString("channel") + ":" + n.getString("accountId")).containsExactly("APP:s09-maria");
        assertThat(notifications("N-06").getFirst().get("variables", Document.class)).containsEntry("time", "8:30–9:00").containsEntry("dog_name", "Rock")
                .containsEntry("ring_name", "Muntanya").containsKey("date");
        assertThat(notifications("N-47")).isEmpty();
        assertThat(mongo.findById("s09-m-maria", Document.class, "members").get("version")).as("E5-T08: lastDogForTraining never bumps Member.version").isEqualTo(memberVersion);
        // A second dog on the same slot and ring: SLOT_TAKEN; the same dog elsewhere at that time: DOG_ALREADY_BOOKED; the default dog is remembered.
        assertThat(code(book(as("joan"), "s09-d-kira", "2026-10-05T08:30", MUN, 409))).isEqualTo("SLOT_TAKEN");
        assertThat(code(book(as("maria"), "s09-d-rock", "2026-10-05T08:30", CEN, 422))).isEqualTo("DOG_ALREADY_BOOKED");
        book(as("maria"), "s09-d-kira", "2026-10-05T10:00", CEN, 201);
        assertThat(call(GET, "/me/training-summary", null, as("maria"), 200).path("defaultDogId").asText()).isEqualTo("s09-d-kira");
    }

    @Test void T_09_17_memberConditionsRejectTheBookingButNeverTheCancellation() throws Exception {
        var kept = book(as("maria"), "s09-d-rock", "2026-10-06T10:00", MUN, 201);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s09-m-maria")), new Update().set("bookingBlock", new Document("active", true).append("reason", "Quota pendent")), "members");
        var blocked = book(as("maria"), "s09-d-rock", "2026-10-07T10:00", MUN, 422);
        assertThat(code(blocked)).isEqualTo("BOOKING_BLOCKED"); assertThat(blocked.at("/details/reason").asText()).isEqualTo("Quota pendent");
        assertThat(call(GET, "/me/training-summary", null, as("maria"), 200).at("/bookingBlock/reason").asText()).isEqualTo("Quota pendent");
        cancel(as("maria"), kept.path("id").asText(), null, 200);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s09-m-maria")), new Update().set("bookingBlock", new Document("active", false)), "members");
        try (var tenant = TenantContext.open(CLUB)) { inactivity.approve("s09-m-maria", LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30")); }
        clock.setInstant(local("2026-10-30T10:00"));
        var inactive = book(as("maria"), "s09-d-rock", "2026-11-02T09:00", MUN, 422);
        assertThat(code(inactive)).isEqualTo("INACTIVITY_PERIOD"); assertThat(inactive.at("/details/from").asText()).isEqualTo("2026-11-01");
        book(as("maria"), "s09-d-rock", "2026-10-31T09:00", MUN, 201);
        assertThat(call(GET, "/me/training-summary?date=2026-11-02", null, as("maria"), 200).at("/inactivity/from").asText()).isEqualTo("2026-11-01");
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.INACTIVITY).toArray(Module[]::new));
        book(as("maria"), "s09-d-rock", "2026-11-02T09:00", MUN, 201);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s09-m-maria")), new Update().set("status", "SUSPENDED"), "members");
        assertThat(code(book(as("maria"), "s09-d-rock", "2026-11-01T09:00", MUN, 422))).isEqualTo("MEMBER_NOT_ACTIVE");
    }

    @Test void T_09_18_aDogInAClassAtThatTimeCannotTrain() throws Exception {
        classSession("s09-class", CEN, "2026-10-06T08:30", "2026-10-06T09:40", "ACTIVE");
        mongo.save(new Document("_id", "s09-class-booking").append("clubId", CLUB).append("classSessionId", "s09-class").append("dogId", "s09-d-rock")
                .append("memberId", "s09-m-maria").append("state", "ACTIVE").append("classStartsAt", Date.from(local("2026-10-06T08:30")))
                .append("classEndsAt", Date.from(local("2026-10-06T09:40"))).append("version", 0L), "bookings");
        assertThat(code(book(as("maria"), "s09-d-rock", "2026-10-06T09:00", MUN, 422))).isEqualTo("DOG_ALREADY_BOOKED");
        book(as("maria"), "s09-d-rock", "2026-10-06T09:30", MUN, 422);
        book(as("maria"), "s09-d-rock", "2026-10-06T10:00", MUN, 201);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s09-class-booking")), new Update().set("state", "CANCELLED"), "bookings");
        book(as("maria"), "s09-d-rock", "2026-10-06T09:00", MUN, 201);
    }

    @Test void T_09_13_T_09_19_T_09_26_ringBlocksOfTheInstructorValidateBlockTheGridAndFeedTheOccupancy() throws Exception {
        var valid = new LinkedHashMap<String, Object>(Map.of("ringId", CAR, "from", local("2026-10-08T18:00").toString(), "to", local("2026-10-08T19:00").toString(),
                "kind", "RESERVATION", "reason", "PRIVATE_CLASS", "note", "Particular amb l'alumna de la tarda"));
        var shortBlock = new LinkedHashMap<>(valid); shortBlock.put("to", local("2026-10-08T18:20").toString());
        assertThat(code(call(POST, "/ring-blocks", shortBlock, as("estel"), 400, UUID.randomUUID().toString()))).isEqualTo("INVALID_TIME_RANGE");
        var misaligned = new LinkedHashMap<>(valid); misaligned.put("from", local("2026-10-08T18:05").toString());
        // E5-T09 (E5-T04 review #9): exactly INVALID_SLOT_GRANULARITY — S06 R-06-11/§6 own the endpoint (`classes.slotMinutes`); T-09-13's
        // INVALID_TIME_RANGE is the too-short case above.
        assertThat(code(call(POST, "/ring-blocks", misaligned, as("estel"), 400, UUID.randomUUID().toString()))).isEqualTo("INVALID_SLOT_GRANULARITY");
        var wrongReason = new LinkedHashMap<>(valid); wrongReason.put("kind", "BLOCK"); wrongReason.put("reason", "THERAPY");
        assertThat(code(call(POST, "/ring-blocks", wrongReason, as("estel"), 400, UUID.randomUUID().toString()))).isEqualTo("VALIDATION_ERROR");
        var horizon = new LinkedHashMap<>(valid); horizon.put("from", local("2026-12-08T18:00").toString()); horizon.put("to", local("2026-12-08T19:00").toString());
        assertThat(code(call(POST, "/ring-blocks", horizon, as("estel"), 400, UUID.randomUUID().toString()))).isEqualTo("VALIDATION_ERROR");
        var created = call(POST, "/ring-blocks", valid, as("estel"), 201, UUID.randomUUID().toString());
        assertThat(eventsOf("RingBlockCreated")).hasSize(1);
        var grid = slots(as("maria"), "2026-10-08", "2026-10-08", null);
        assertThat(cell(grid, "2026-10-08T18:00", CAR).path("reason").asText()).isEqualTo("RING_BLOCK");
        assertThat(cell(grid, "2026-10-08T18:30", CAR).path("reason").asText()).isEqualTo("RING_BLOCK");
        assertThat(cell(grid, "2026-10-08T19:00", CAR).path("state").asText()).isEqualTo("FREE");
        book(as("pau"), "s09-d-blat", "2026-10-08T10:00", MUN, 201);
        try (var tenant = TenantContext.open(CLUB)) {
            var from = local("2026-10-08T00:00"); var to = local("2026-10-09T00:00");
            var staff = occupancy.occupancy(from, to, null, "INSTRUCTOR");
            assertThat(staff).extracting(i -> i.type() + ":" + i.reason()).containsExactly("TRAINING:TRAINING", "RING_BLOCK:PRIVATE_CLASS");
            assertThat(staff.get(0).memberName()).isEqualTo("Pau"); assertThat(staff.get(0).dogName()).isEqualTo("Blat");
            assertThat(staff.get(1).note()).isEqualTo("Particular amb l'alumna de la tarda");
            var member = occupancy.occupancy(from, to, null, "MEMBER");
            assertThat(member).allSatisfy(i -> { assertThat(i.memberName()).isNull(); assertThat(i.dogName()).isNull(); assertThat(i.note()).isNull(); });
            assertThat(member).extracting(i -> i.type() + ":" + i.reason()).containsExactly("TRAINING:TRAINING", "RING_BLOCK:PRIVATE_CLASS");
            assertThat(member).allSatisfy(i -> assertThat(i.id()).as("no booking or block id for a MEMBER (E5-T09)").isNull());
            assertThat(staff).allSatisfy(i -> assertThat(i.id()).isNotBlank());
            assertThat(occupancy.occupancy(from, to, List.of(MUN), "INSTRUCTOR")).hasSize(1);
        }
        // T-09-26 (E4-T03 rules, re-checked with S09 data): stale version, a started block, an activity block.
        String id = created.path("id").asText();
        var patched = call(PATCH, "/ring-blocks/" + id, Map.of("note", "Canvi", "version", 0), as("estel"), 200);
        assertThat(code(call(PATCH, "/ring-blocks/" + id, Map.of("note", "Altra", "version", 0), as("admin"), 409))).isEqualTo("STALE_VERSION");
        clock.setInstant(local("2026-10-08T18:10"));
        assertThat(code(call(PATCH, "/ring-blocks/" + id, Map.of("note", "Tard", "version", patched.path("version").asLong()), as("estel"), 409))).isEqualTo("INVALID_STATE");
        block("s09-activity-block", MUN, "2026-10-09T09:00", "2026-10-09T13:00", "BLOCK", "ACTIVITY", null, "s09-activity");
        assertThat(code(call(PATCH, "/ring-blocks/s09-activity-block", Map.of("note", "x", "version", 0), as("admin"), 422))).isEqualTo("RING_BLOCK_MANAGED_BY_ACTIVITY");
    }

    @Test void T_09_20_T_09_21_aBlockOverALiveBookingNeedsTheAdminAndCancelsItInTheSameTransaction() throws Exception {
        var thai = book(as("sergio"), "s09-d-thai", "2026-10-06T17:00", CAR, 201);
        var body = new LinkedHashMap<String, Object>(Map.of("ringId", CAR, "from", local("2026-10-06T16:00").toString(), "to", local("2026-10-06T18:00").toString(),
                "kind", "BLOCK", "reason", "MAINTENANCE"));
        var refused = call(POST, "/ring-blocks", body, as("estel"), 422, UUID.randomUUID().toString());
        assertThat(code(refused)).isEqualTo("RING_HAS_BOOKINGS");
        assertThat(refused.at("/details/bookings/0/memberName").asText()).isEqualTo("Sergio"); assertThat(refused.at("/details/bookings/0/dogName").asText()).isEqualTo("Thai");
        body.put("cancelBookings", true);
        assertThat(code(call(POST, "/ring-blocks", body, as("estel"), 403, UUID.randomUUID().toString()))).isEqualTo("FORBIDDEN");
        assertThat(count("ring_blocks", new Criteria())).isZero();
        call(POST, "/ring-blocks", body, as("admin"), 201, UUID.randomUUID().toString());
        var stored = training(thai.path("id").asText());
        assertThat(stored.getString("state")).isEqualTo("CANCELLED_BY_CLUB"); assertThat(stored.getString("cancelReason")).isEqualTo("RING_BLOCK");
        assertThat(stored.getString("cancelledBy")).isEqualTo("ADMIN");
        assertThat(eventsOf("TrainingCancelled").getFirst().get("payload", Document.class)).containsEntry("by", "ADMIN").containsEntry("origin", "BACKOFFICE")
                .containsEntry("cancelReason", "RING_BLOCK").containsEntry("late", false);
        assertThat(eventsOf("TrainingCancelled").getFirst().getString("actorAccountId")).isEqualTo("s09-admin");
        assertThat(count("ring_blocks", new Criteria())).isEqualTo(1);
        dispatch(); dispatch();
        assertThat(notifications("N-47")).extracting(n -> n.getString("channel") + ":" + n.getString("status")).containsExactlyInAnyOrder("APP:SENT", "EMAIL:SENT", "SMS:QUEUED");
        assertThat(notifications("N-07")).isEmpty();
        assertThat(cell(slots(as("pau"), "2026-10-06", "2026-10-06", null), "2026-10-06T17:00", CAR).path("reason").asText()).isEqualTo("RING_BLOCK");
    }

    @Test @AuditCovers({AuditAction.TRAINING_BOOKED_BY_CLUB, AuditAction.TRAINING_CANCELLED_BY_CLUB})
    void T_09_23_impersonationIsBackofficeAuditedMayOverrideTheLimitAndCancelLateWithAReason() throws Exception {
        var admin = impersonating("admin", "s09-m-maria");
        var booked = book(admin, "s09-d-rock", "2026-10-05T10:00", MUN, 201);
        assertThat(booked.path("origin").asText()).isEqualTo("BACKOFFICE"); assertThat(booked.at("/impersonation/actorName").asText()).isEqualTo("Example admin");
        var stored = training(booked.path("id").asText());
        assertThat(stored.getString("createdByAccountId")).isEqualTo("s09-admin"); assertThat(stored.getString("impersonatedByAccountId")).isEqualTo("s09-admin");
        var audit = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("TRAINING_BOOKED_BY_CLUB")), Document.class, "audit_entries");
        assertThat(audit).containsEntry("actorAccountId", "s09-admin").containsEntry("impersonatedMemberId", "s09-m-maria").containsEntry("entityId", booked.path("id").asText());
        // The normal token may not override the limit.
        var body = Map.of("dogId", "s09-d-rock", "startsAt", local("2026-10-06T10:00").toString(), "override", Map.of("limit", true, "reason", "Preparació de prova"));
        assertThat(code(call(POST, "/training-bookings", body, as("maria"), 403, UUID.randomUUID().toString()))).isEqualTo("OVERRIDE_NOT_ALLOWED");
        book(as("maria"), "s09-d-rock", "2026-10-06T10:00", MUN, 201); book(as("maria"), "s09-d-rock", "2026-10-07T10:00", MUN, 201);
        assertThat(code(book(as("maria"), "s09-d-rock", "2026-10-08T10:00", MUN, 409))).isEqualTo("TRAINING_LIMIT_REACHED");
        var over = call(POST, "/training-bookings", Map.of("dogId", "s09-d-rock", "startsAt", local("2026-10-08T10:00").toString(),
                "override", Map.of("limit", true, "reason", "Preparació de prova")), impersonating("admin", "s09-m-maria"), 201, UUID.randomUUID().toString());
        assertThat(over.at("/counter/used").asInt()).isEqualTo(4);
        assertThat(count("audit_entries", Criteria.where("action").is("TRAINING_BOOKED_BY_CLUB").and("reason").is("Preparació de prova"))).isEqualTo(1);
        // The window is never skipped by the override.
        assertThat(code(call(POST, "/training-bookings", Map.of("dogId", "s09-d-rock", "startsAt", local("2026-10-10T10:00").toString(),
                "override", Map.of("limit", true, "reason", "Prova")), impersonating("admin", "s09-m-maria"), 422, UUID.randomUUID().toString()))).isEqualTo("SLOT_OUT_OF_WINDOW");
        // Late: the member is refused; the impersonating admin needs a reason (ADMIN_LATE, audited).
        clock.setInstant(local("2026-10-05T09:00"));
        assertThat(code(cancel(as("maria"), booked.path("id").asText(), null, 422))).isEqualTo("TRAINING_CANCEL_TOO_LATE");
        assertThat(code(cancel(impersonating("admin", "s09-m-maria"), booked.path("id").asText(), null, 400))).isEqualTo("VALIDATION_ERROR");
        var late = cancel(impersonating("admin", "s09-m-maria"), booked.path("id").asText(), "Lesió del gos", 200);
        assertThat(late.path("cancelReason").asText()).isEqualTo("ADMIN_LATE"); assertThat(late.path("cancelledBy").asText()).isEqualTo("ADMIN");
        assertThat(count("audit_entries", Criteria.where("action").is("TRAINING_CANCELLED_BY_CLUB").and("impersonatedMemberId").is("s09-m-maria")
                .and("reason").is("Lesió del gos"))).isEqualTo(1);
        var event = eventsOf("TrainingCancelled").getFirst();
        assertThat(event.get("payload", Document.class)).containsEntry("late", true).containsEntry("by", "ADMIN").containsEntry("origin", "BACKOFFICE");
        dispatch(); dispatch();
        // Booked by the club: N-06 + N-47 (two bookings); cancelled by the club: N-47 only, never N-07.
        assertThat(count("notifications", Criteria.where("code").is("N-47").and("channel").is("SMS"))).isEqualTo(3);
        assertThat(notifications("N-07")).isEmpty();
        var sms = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-47").and("channel").is("SMS")), Document.class, "notifications");
        assertThat(sms.getString("status")).isEqualTo("QUEUED");
        // SMS off: the intent is recorded as SKIPPED_MODULE_OFF.
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.SMS).toArray(Module[]::new));
        var kira = book(impersonating("admin", "s09-m-maria"), "s09-d-kira", "2026-10-06T12:00", MUN, 201); dispatch();
        assertThat(count("notifications", Criteria.where("code").is("N-47").and("channel").is("SMS").and("status").is("SKIPPED_MODULE_OFF"))).isEqualTo(1);
        // E5-T09 (E5-T04 review #6): R-09-10 and R-09-16 set no end to the admin's late cancellation: at `now ≥ endsAt` it still works.
        clock.setInstant(local("2026-10-06T12:30"));
        assertThat(code(cancel(as("maria"), kira.path("id").asText(), null, 422))).isEqualTo("TRAINING_CANCEL_TOO_LATE");
        var ended = cancel(impersonating("admin", "s09-m-maria"), kira.path("id").asText(), "Registre erroni", 200);
        assertThat(ended.path("state").asText()).isEqualTo("CANCELLED"); assertThat(ended.path("cancelReason").asText()).isEqualTo("ADMIN_LATE");
        clock.setInstant(local("2026-10-06T18:00"));
        assertThat(code(cancel(impersonating("admin", "s09-m-maria"), kira.path("id").asText(), "Altra vegada", 409))).isEqualTo("INVALID_STATE");
    }

    @Test void T_09_24_T_09_30_theUsageRegisterIsAUniversalListForStaffOnly() throws Exception {
        book(as("maria"), "s09-d-rock", "2026-10-05T10:00", MUN, 201); var pau = book(as("pau"), "s09-d-blat", "2026-10-06T10:00", CEN, 201);
        cancel(as("pau"), pau.path("id").asText(), null, 200);
        var page = call(GET, "/training-bookings?filter=state:eq:ACTIVE", null, as("admin"), 200);
        assertThat(page.path("items")).hasSize(1);
        var item = page.path("items").get(0);
        assertThat(item.path("ringName").asText()).isEqualTo("Muntanya"); assertThat(item.path("memberName").asText()).isEqualTo("Maria Example");
        assertThat(item.path("dogName").asText()).isEqualTo("Rock"); assertThat(item.path("date").asText()).isEqualTo("2026-10-05");
        assertThat(item.path("startsAtLocal").asText()).isEqualTo("10:00");
        assertThat(call(GET, "/training-bookings?filter=date:eq:2026-10-06", null, as("estel"), 200).path("items")).extracting(i -> i.path("state").asText()).containsExactly("CANCELLED");
        assertThat(call(GET, "/training-bookings?filter=ringId:eq:" + CEN, null, as("estel"), 200).path("totalItems").asInt()).isEqualTo(1);
        assertThat(code(call(GET, "/training-bookings?filter=seatIndex:eq:0", null, as("admin"), 400))).isEqualTo("INVALID_FILTER");
        call(GET, "/training-bookings", null, as("maria"), 403);
        call(GET, "/training-bookings", null, impersonating("admin", "s09-m-maria"), 403);
        var file = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/training-bookings/export?format=xlsx")
                .header("Host", HOST).with(as("admin"))).andReturn().getResponse();
        assertThat(file.getStatus()).isEqualTo(200); assertThat(file.getHeader("Content-Disposition")).contains(".xlsx");
        // T-09-30: roles and tenant.
        call(POST, "/ring-blocks", Map.of("ringId", CAR, "from", local("2026-10-08T18:00").toString(), "to", local("2026-10-08T19:00").toString(), "kind", "BLOCK",
                "reason", "MAINTENANCE"), as("maria"), 403, UUID.randomUUID().toString());
        assertThat(code(book(as("pau"), "s09-d-rock", "2026-10-07T10:00", MUN, 404))).isEqualTo("DOG_NOT_ACCESSIBLE");
        book(as("estel"), "s09-d-blat", "2026-10-07T10:00", MUN, 403);
        var foreign = jwt(OTHER);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/training-bookings/" + pau.path("id").asText())
                .header("Host", OTHER_HOST).with(foreign)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(dashboard.bookings(CLUB, local("2026-10-05T00:00"), local("2026-10-07T00:00"))).extracting(TrainingBookingsQuery.Booking::status)
                    .containsExactlyInAnyOrder("ACTIVE", "CANCELLED");
            assertThatThrownBy(() -> dashboard.bookings(OTHER, NOW, NOW)).isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class);
        }
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor jwt(String club) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(j -> j.subject("s09-admin").claim("clubId", club)).authorities(() -> "ROLE_ADMIN");
    }

    @Test void T_09_28_T_05_28_conflictsSeeLiveBookingsAndARingThatStopsBeingReservableCancelsThemOnlyOnRequest() throws Exception {
        var cadells = book(as("pau"), "s09-d-blat", "2026-10-06T10:00", CAD, 201);
        var classBody = new LinkedHashMap<String, Object>(Map.of("date", "2026-10-06", "startTime", "09:40", "endTime", "10:40", "ringId", CAD,
                "levelIds", List.of("s09-lv-D"), "instructorIds", List.of("s09-instructor"), "description", "Classe"));
        var refused = call(POST, "/class-sessions", classBody, as("admin"), 422);
        assertThat(code(refused)).isEqualTo("RING_HAS_BOOKINGS");
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(conflicts.findActiveBookings(CAD, local("2026-10-06T00:00"), local("2026-10-07T00:00"))).extracting(TrainingConflictPort.Booking::id)
                    .containsExactly(cadells.path("id").asText());
            assertThatThrownBy(() -> conflicts.cancelByClub(List.of(cadells.path("id").asText()), "RING_NOT_RESERVABLE"))
                    .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        }
        // S05 R-05-08 (amended 2026-09-24, S09 R-09-13 wins): removing «reservable» with a live booking is RING_HAS_BOOKINGS{bookings[]}
        // (422, CATALEG_ERRORS rule 0); nothing changes until the ADMIN sends cancelBookings: true.
        var ring = call(GET, "/rings/" + CAD, null, as("admin"), 200);
        var withBookings = call(PATCH, "/rings/" + CAD, Map.of("allowsFreeTraining", false, "version", ring.path("version").asLong()), as("admin"), 422);
        assertThat(code(withBookings)).isEqualTo("RING_HAS_BOOKINGS");
        assertThat(withBookings.at("/details/bookings/0/id").asText()).isEqualTo(cadells.path("id").asText());
        assertThat(withBookings.at("/details/bookings/0/memberName").asText()).isEqualTo("Pau"); assertThat(withBookings.at("/details/bookings/0/dogName").asText()).isEqualTo("Blat");
        assertThat(training(cadells.path("id").asText())).containsEntry("state", "ACTIVE");
        assertThat(call(GET, "/rings/" + CAD, null, as("admin"), 200).path("allowsFreeTraining").asBoolean()).isTrue();
        var changed = call(PATCH, "/rings/" + CAD, Map.of("allowsFreeTraining", false, "version", ring.path("version").asLong(), "cancelBookings", true), as("admin"), 200);
        assertThat(changed.path("allowsFreeTraining").asBoolean()).isFalse();
        assertThat(training(cadells.path("id").asText())).containsEntry("state", "CANCELLED_BY_CLUB").containsEntry("cancelReason", "RING_NOT_RESERVABLE")
                .containsEntry("cancelledBy", "ADMIN");
        assertThat(eventsOf("TrainingCancelled")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class))
                .containsEntry("by", "ADMIN").containsEntry("origin", "BACKOFFICE").containsEntry("cancelReason", "RING_NOT_RESERVABLE"));
        assertThat(eventsOf("RingChanged")).isNotEmpty();
        // Without bookings the free slots of the ring are gone: /training-slots no longer lists it.
        var grid = slots(as("maria"), "2026-10-06", "2026-10-06", null);
        assertThat(cell(grid, "2026-10-06T11:00", MUN).isMissingNode()).isFalse(); assertThat(cell(grid, "2026-10-06T11:00", CAD).isMissingNode()).isTrue();
        // Deactivating a ring with a live training booking follows the same rule (R-05-07's RING_IN_USE stays for future classes).
        var carretera = book(as("julia"), "s09-d-lluna", "2026-10-07T11:00", CAR, 201);
        var car = call(GET, "/rings/" + CAR, null, as("admin"), 200);
        assertThat(code(call(PATCH, "/rings/" + CAR, Map.of("active", false, "version", car.path("version").asLong()), as("admin"), 422))).isEqualTo("RING_HAS_BOOKINGS");
        call(PATCH, "/rings/" + CAR, Map.of("active", false, "version", car.path("version").asLong(), "cancelBookings", true), as("admin"), 200);
        assertThat(training(carretera.path("id").asText())).containsEntry("state", "CANCELLED_BY_CLUB").containsEntry("cancelReason", "RING_NOT_RESERVABLE");
        // S06 with cancelBookings: the class is created and the booking goes as CLASS_CONFLICT.
        var other = book(as("julia"), "s09-d-lluna", "2026-10-06T10:00", CEN, 201);
        classBody.put("ringId", CEN); classBody.put("cancelBookings", true);
        call(POST, "/class-sessions", classBody, as("admin"), 201);
        assertThat(training(other.path("id").asText())).containsEntry("state", "CANCELLED_BY_CLUB").containsEntry("cancelReason", "CLASS_CONFLICT");
    }

    /**
     * E5-T15 (R-09-13 on the week paths; review E5-T07 «Not checked»): the week generation places its classes without
     * asking S09, so a generated class over a live training booking is a `RING_TRAINING_CONFLICT` (R-06-05) that blocks
     * the validation (R-06-08) until the booking goes; the DRAFT class already refuses new bookings of its slot (R-09-03).
     */
    @Test void T_06_12_R_09_13_aGeneratedClassOverALiveTrainingBookingMakesTheWeekInconsistentAndBlocksItsValidation() throws Exception {
        String booking = book(as("pau"), "s09-d-blat", "2026-10-06T10:00", MUN, 201).path("id").asText();
        var week = plannedWeek("2026-10-05", MUN, "TUESDAY", "10:00", "11:00");
        assertThat(generate(week).path("classCount").asInt()).isEqualTo(1);
        var calendar = call(GET, "/weeks/" + week.weekId() + "/calendar?filter=DRAFT", null, as("admin"), 200);
        assertThat(calendar.path("inconsistencies")).singleElement().satisfies(i -> assertThat(i.path("type").asText()).isEqualTo("RING_TRAINING_CONFLICT"));
        assertThat(calendar.path("canValidate").asBoolean()).isFalse();
        var refused = call(POST, "/weeks/" + week.weekId() + "/validation", Map.of(), as("admin"), 422); // CATALEG_ERRORS rule 0
        assertThat(code(refused)).isEqualTo("WEEK_INCONSISTENT");
        assertThat(refused.at("/details/inconsistencies/0/type").asText()).isEqualTo("RING_TRAINING_CONFLICT");
        assertThat(count("class_sessions", Criteria.where("weekId").is(week.weekId()).and("state").is("DRAFT"))).as("no class changes").isEqualTo(1);
        assertThat(training(booking)).containsEntry("state", "ACTIVE");
        // The DRAFT class holds its slot: nobody else can book it.
        assertThat(book(as("julia"), "s09-d-lluna", "2026-10-06T10:00", MUN, 409).at("/details/reason").asText()).isEqualTo("CLASS");
        // Once the booking is cancelled, the week validates.
        cancel(as("pau"), booking, null, 200);
        assertThat(call(POST, "/weeks/" + week.weekId() + "/validation", Map.of(), as("admin"), 200).path("validatedClassIds")).hasSize(1);
        assertThat(count("class_sessions", Criteria.where("weekId").is(week.weekId()).and("state").is("ACTIVE"))).isEqualTo(1);
    }

    @Test void T_09_29_aNewClassInvalidatesTheWarmGridCacheOfItsDay() throws Exception {
        assertThat(cell(slots(as("maria"), "2026-10-05", "2026-10-05", null), "2026-10-05T18:00", MUN).path("state").asText()).isEqualTo("FREE");
        classSession("s09-late-class", MUN, "2026-10-05T18:00", "2026-10-05T19:00", "ACTIVE");
        assertThat(cell(slots(as("maria"), "2026-10-05", "2026-10-05", null), "2026-10-05T18:00", MUN).path("state").asText()).as("still cached").isEqualTo("FREE");
        // The booking path never reads the cache: the class already blocks a booking.
        assertThat(book(as("maria"), "s09-d-rock", "2026-10-05T18:00", MUN, 409).at("/details/reason").asText()).isEqualTo("CLASS");
        publish(new com.agilityhub.core.support.TestEvent(null, "ClassSessionCreated", CLUB, "ClassSession", "s09-late-class", clock.instant(), Map.of("classId", "s09-late-class"),
                "s09-admin", null, com.agilityhub.core.shared.domain.DomainEvent.Origin.BACKOFFICE));
        dispatch();
        assertThat(cell(slots(as("maria"), "2026-10-05", "2026-10-05", null), "2026-10-05T18:00", MUN).path("reason").asText()).isEqualTo("CLASS");
        // A parameter outside S09 keeps the cache; `training.slotMinutes` drops it for the whole club.
        classSession("s09-other-class", CEN, "2026-10-05T18:00", "2026-10-05T19:00", "ACTIVE");
        publish(new com.agilityhub.core.support.TestEvent(null, "ParameterChanged", CLUB, "Parameter", "x", clock.instant(), Map.of("key", "classes.minDogs"), null, null,
                com.agilityhub.core.shared.domain.DomainEvent.Origin.BACKOFFICE));
        dispatch();
        assertThat(cell(slots(as("maria"), "2026-10-05", "2026-10-05", null), "2026-10-05T18:00", CEN).path("state").asText()).isEqualTo("FREE");
        publish(new com.agilityhub.core.support.TestEvent(null, "ParameterChanged", CLUB, "Parameter", "x", clock.instant(), Map.of("key", "training.slotMinutes"), null, null,
                com.agilityhub.core.shared.domain.DomainEvent.Origin.BACKOFFICE));
        dispatch();
        assertThat(cell(slots(as("maria"), "2026-10-05", "2026-10-05", null), "2026-10-05T18:00", CEN).path("reason").asText()).isEqualTo("CLASS");
        // A block's days, and the 60 s TTL for anything the events miss.
        publish(new com.agilityhub.core.support.TestEvent(null, "RingBlockCreated", CLUB, "RingBlock", "b", clock.instant(),
                Map.of("from", local("2026-10-05T19:00").toString(), "to", local("2026-10-05T20:00").toString()), null, null, com.agilityhub.core.shared.domain.DomainEvent.Origin.APP));
        dispatch();
        classSession("s09-ttl-class", CAR, "2026-10-05T18:00", "2026-10-05T19:00", "ACTIVE");
        assertThat(cell(slots(as("maria"), "2026-10-05", "2026-10-05", null), "2026-10-05T18:00", CAR).path("reason").asText()).isEqualTo("CLASS");
        classSession("s09-ttl-class-2", CAD, "2026-10-05T18:00", "2026-10-05T19:00", "ACTIVE");
        assertThat(cell(slots(as("maria"), "2026-10-05", "2026-10-05", null), "2026-10-05T18:00", CAD).path("state").asText()).isEqualTo("FREE");
        clock.advance(Duration.ofSeconds(61));
        assertThat(cell(slots(as("maria"), "2026-10-05", "2026-10-05", null), "2026-10-05T18:00", CAD).path("reason").asText()).isEqualTo("CLASS");
    }

    /** E5-T09 (E5-T04 review #7): the outbox JSON of a real ring block carries ISO instants, so only the block's days are dropped. */
    @Test void T_09_29_aRealRingBlockEventInvalidatesOnlyTheDaysOfTheBlock() throws Exception {
        slots(as("maria"), "2026-10-06", "2026-10-07", null); // warm both days
        classSession("s09-uncached-06", MUN, "2026-10-06T18:00", "2026-10-06T19:00", "ACTIVE");
        classSession("s09-uncached-07", MUN, "2026-10-07T18:00", "2026-10-07T19:00", "ACTIVE");
        call(POST, "/ring-blocks", Map.of("ringId", CAR, "from", local("2026-10-06T18:00").toString(), "to", local("2026-10-06T19:00").toString(),
                "kind", "BLOCK", "reason", "MAINTENANCE"), as("admin"), 201, UUID.randomUUID().toString());
        var created = eventsOf("RingBlockCreated").getFirst();
        assertThat(created.getString("eventJson")).contains("\"from\":\"" + local("2026-10-06T18:00") + "\"", "\"to\":\"" + local("2026-10-06T19:00") + "\"");
        dispatch();
        var grid = slots(as("maria"), "2026-10-06", "2026-10-07", null);
        assertThat(cell(grid, "2026-10-06T18:00", CAR).path("reason").asText()).isEqualTo("RING_BLOCK");
        assertThat(cell(grid, "2026-10-06T18:00", MUN).path("reason").asText()).as("the block's day is reloaded").isEqualTo("CLASS");
        assertThat(cell(grid, "2026-10-07T18:00", MUN).path("state").asText()).as("another day stays cached").isEqualTo("FREE");
    }

    @Autowired Map<String, com.agilityhub.core.shared.application.DomainEventHandler<?>> handlers;
    @Test void T_09_29_everyTrainingConsumerReadsACatalogEventAndOnlyDropsTheCache() throws Exception {
        var catalog = java.nio.file.Files.readString(java.nio.file.Path.of("docs/specs/00-transversal/CATALEG_ESDEVENIMENTS.md"));
        var training = handlers.entrySet().stream().filter(e -> e.getKey().startsWith("training")).toList();
        assertThat(training).hasSize(19);
        classSession("s09-known", MUN, "2026-10-05T18:00", "2026-10-05T19:00", "ACTIVE");
        for (var entry : training) {
            @SuppressWarnings("unchecked") var handler = (com.agilityhub.core.shared.application.DomainEventHandler<TrainingForeignEvent>) entry.getValue();
            assertThat(handler.eventClass()).isEqualTo(TrainingForeignEvent.class);
            assertThat(catalog).as(entry.getKey()).contains("`" + handler.eventType());
            for (var payload : List.<Map<String, Object>>of(Map.of(), Map.of("classId", "s09-known", "key", "training.maxPerWeek"), Map.of("from", "not-an-instant", "to", "x"))) {
                slots(as("maria"), "2026-10-05", "2026-10-05", null); // warm
                handler.handle("event-" + entry.getKey(), new TrainingForeignEvent(handler.eventType(), null, CLUB, "Any", "missing", clock.instant(), payload, null, null,
                        com.agilityhub.core.shared.domain.DomainEvent.Origin.SYSTEM));
            }
        }
        assertThat(count("training_bookings", new Criteria())).isZero();
        assertThat(cell(slots(as("maria"), "2026-10-05", "2026-10-05", null), "2026-10-05T18:00", MUN).path("reason").asText()).isEqualTo("CLASS");
    }

    @Test void T_09_31_withFreeTrainingOffTheRoutesAreGoneAndOnlyBlocksRemain() throws Exception {
        var rock = book(as("maria"), "s09-d-rock", "2026-10-06T10:00", MUN, 201);
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.FREE_TRAINING).toArray(Module[]::new));
        for (String path : List.of("/training-slots?from=2026-10-05&to=2026-10-05", "/me/training-summary", "/me/training-bookings", "/training-bookings/" + rock.path("id").asText())) {
            assertThat(code(call(GET, path, null, as("maria"), 404))).isEqualTo("MODULE_DISABLED");
        }
        assertThat(code(call(GET, "/training-bookings", null, as("admin"), 404))).isEqualTo("MODULE_DISABLED");
        assertThat(code(book(as("maria"), "s09-d-rock", "2026-10-07T10:00", MUN, 404))).isEqualTo("MODULE_DISABLED");
        var block = new LinkedHashMap<String, Object>(Map.of("ringId", CAR, "from", local("2026-10-08T18:00").toString(), "to", local("2026-10-08T19:00").toString(),
                "kind", "RESERVATION", "reason", "PRIVATE_CLASS"));
        assertThat(code(call(POST, "/ring-blocks", block, as("estel"), 404, UUID.randomUUID().toString()))).isEqualTo("MODULE_DISABLED");
        block.put("kind", "BLOCK"); block.put("reason", "MAINTENANCE");
        call(POST, "/ring-blocks", block, as("estel"), 201, UUID.randomUUID().toString());
        // A booking over the live training booking is no conflict with the module off (S06 skips the port).
        call(POST, "/ring-blocks", Map.of("ringId", MUN, "from", local("2026-10-06T10:00").toString(), "to", local("2026-10-06T11:00").toString(), "kind", "BLOCK",
                "reason", "MAINTENANCE"), as("estel"), 201, UUID.randomUUID().toString());
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(occupancy.occupancy(local("2026-10-06T00:00"), local("2026-10-09T00:00"), null, "INSTRUCTOR")).extracting(i -> i.type())
                    .containsOnly(TrainingOccupancyPort.Type.RING_BLOCK);
        }
        var grid = call(GET, "/day-grid?date=2026-10-06&view=instructor", null, as("estel"), 200);
        assertThat(grid.toString()).doesNotContain("TRAINING");
    }

    @Test void T_09_27_theDayGridShowsTrainingAsOccupiedForMembersAndNamedForStaff() throws Exception {
        String bookingId = book(as("pau"), "s09-d-blat", "2026-10-06T10:00", MUN, 201).path("id").asText();
        block("s09-private", CAR, "2026-10-06T18:00", "2026-10-06T19:00", "RESERVATION", "PRIVATE_CLASS", "Particular", null);
        var member = call(GET, "/day-grid?date=2026-10-06&view=member", null, as("maria"), 200);
        var staff = call(GET, "/day-grid?date=2026-10-06&view=instructor", null, as("estel"), 200);
        System.out.println("T-09-27 day-grid view=member: " + member);
        System.out.println("T-09-27 day-grid view=instructor: " + staff);
        assertThat(member.toString()).contains("OCCUPIED", "TRAINING", "PRIVATE_CLASS").doesNotContain("Pau", "Blat", "Particular");
        assertThat(staff.toString()).contains("Pau + Blat", "Particular", "\"kind\":\"TRAINING\"");
        // The booking id never leaks to a member through the grid; the staff view links it.
        assertThat(member.toString()).doesNotContain(bookingId); assertThat(staff.toString()).contains(bookingId);
        // E5-T09: nor the block id (R-09-12 `{type, reason}`), and the block is still one cell, not a copy per source.
        assertThat(member.toString()).doesNotContain("s09-private"); assertThat(staff.toString()).contains("s09-private");
        assertThat(member.toString().split("PRIVATE_CLASS", -1)).hasSize(2); assertThat(staff.toString().split("PRIVATE_CLASS", -1)).hasSize(2);
    }

    @Test void systemCancellationsFollowR_09_14WithoutNotifyingMembersWhoLeft() throws Exception {
        var rock = book(as("maria"), "s09-d-rock", "2026-10-06T10:00", MUN, 201); var rock2 = book(as("maria"), "s09-d-rock", "2026-10-07T10:00", MUN, 201);
        var blat = book(as("pau"), "s09-d-blat", "2026-10-06T10:00", CEN, 201); var lluna = book(as("julia"), "s09-d-lluna", "2026-10-06T11:00", CEN, 201);
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(service.cancelForDog("s09-d-rock")).isEqualTo(2);
            assertThat(service.cancelFutureByMember("s09-m-pau", TrainingCancelledBy.SYSTEM, TrainingCancelReason.MEMBER_LEFT)).isEqualTo(1);
            assertThat(service.cancelForInactivity("s09-m-julia", LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-06"))).isEqualTo(1);
            assertThat(service.cancelForDog("s09-d-rock")).isZero();
            parameter("inactivity.cancelBookingsOnApproval", false);
            assertThat(service.cancelForInactivity("s09-m-julia", LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-06"))).isZero();
        }
        assertThat(training(rock.path("id").asText())).containsEntry("state", "CANCELLED").containsEntry("cancelledBy", "SYSTEM").containsEntry("cancelReason", "MEMBER_LEFT");
        assertThat(training(rock2.path("id").asText())).containsEntry("state", "CANCELLED");
        assertThat(training(blat.path("id").asText())).containsEntry("cancelReason", "MEMBER_LEFT");
        assertThat(training(lluna.path("id").asText())).containsEntry("cancelReason", "INACTIVITY");
        assertThat(eventsOf("TrainingCancelled")).allSatisfy(e -> assertThat(e.get("payload", Document.class)).containsEntry("by", "SYSTEM").containsEntry("origin", "SYSTEM"));
        dispatch(); dispatch();
        assertThat(notifications("N-07")).extracting(n -> n.getString("accountId")).containsExactly("s09-julia");
        assertThat(notifications("N-47")).isEmpty();
    }
}
