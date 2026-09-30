package com.agilityhub.core.clubs.training.api;

import com.agilityhub.core.support.SnapshotSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;

/**
 * E5-T29 over real Mongo: the ring-usage register `/entrenaments` (web E5-W03) — a training booking's end and member number,
 * a ring block's ring, and `filter-values` for `/training-bookings` and `/ring-blocks`, counted over the whole filtered set.
 * Every answer is checked against the committed snapshot's schema.
 */
class TrainingRegisterContractIT extends TrainingFixtures {
    static JsonNode row(JsonNode items, String id) {
        for (var item : items) { if (item.path("id").asText().equals(id)) { return item; } }
        throw new AssertionError("no row " + id + " in " + items);
    }

    /**
     * Step 3: each row of `GET /training-bookings` carries `endsAt` and `endsAtLocal` (like `startsAt` and `startsAtLocal`) and the
     * member's number, null for a member without one; the three keys are in the list's `x-fields`, so `fields` selects them.
     */
    @Test void S09_2_T_09_30_theRegisterRowsCarryTheEndAndTheMemberNumber() throws Exception {
        var rock = book(as("maria"), "s09-d-rock", "2026-10-06T09:30", CEN, 201);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s09-m-pau")), new Update().unset("memberNumber"), "members");
        var blat = book(as("pau"), "s09-d-blat", "2026-10-05T09:00", MUN, 201);
        var items = call(GET, "/training-bookings", null, as("admin"), 200).path("items");
        var maria = row(items, rock.path("id").asText());
        assertThat(maria.path("endsAt").asText()).isEqualTo(rock.path("endsAt").asText());
        assertThat(maria.path("endsAtLocal").asText()).isEqualTo(rock.path("endsAtLocal").asText()).isEqualTo("10:00");
        long number = mongo.findById("s09-m-maria", Document.class, "members").get("memberNumber", Number.class).longValue();
        assertThat(maria.path("memberNumber").asLong()).isEqualTo(number);
        var pau = row(items, blat.path("id").asText());
        assertThat(pau.has("memberNumber")).isTrue(); assertThat(pau.path("memberNumber").isNull()).isTrue();
        assertThat(pau.path("endsAtLocal").asText()).isEqualTo("09:30");
        for (var item : items) { SnapshotSchemas.assertConforms(item, "TrainingBookingListItem"); }
        var sparse = call(GET, "/training-bookings?fields=endsAt,endsAtLocal,memberNumber", null, as("estel"), 200).path("items");
        assertThat(SnapshotSchemas.keys(row(sparse, rock.path("id").asText()))).containsExactlyInAnyOrder("id", "endsAt", "endsAtLocal", "memberNumber");
        assertThat(row(sparse, blat.path("id").asText()).path("memberNumber").isNull()).isTrue();
    }

    /**
     * Step 5: each row of `GET /ring-blocks` carries its ring's name and colour, a deactivated ring's included (the register
     * shows history); `fields` selects them.
     */
    @Test void S09_2_T_09_27_theRingBlockRowsCarryTheirRing() throws Exception {
        block("s09-block-car", CAR, "2026-10-06T16:00", "2026-10-06T18:00", "BLOCK", "MAINTENANCE", "Reg de la pista", null);
        block("s09-block-pet", PET, "2026-10-07T16:00", "2026-10-07T17:00", "BLOCK", "MAINTENANCE", null, null);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(PET)), new Update().set("active", false).set("color", "#AA5500"), "rings");
        for (String reader : List.of("admin", "estel", "maria")) {
            var items = call(GET, "/ring-blocks", null, as(reader), 200).path("items");
            var car = row(items, "s09-block-car");
            assertThat(car.path("ringName").asText()).isEqualTo("Carretera"); assertThat(car.path("ringColor").asText()).isEqualTo("#8FCE8F");
            var inactive = row(items, "s09-block-pet");
            assertThat(inactive.path("ringName").asText()).isEqualTo("Petita"); assertThat(inactive.path("ringColor").asText()).isEqualTo("#AA5500");
            for (var item : items) { assertThat(SnapshotSchemas.violations(item, "RingBlockListItem")).as(reader + " " + item).isEmpty(); }
        }
        var sparse = row(call(GET, "/ring-blocks?fields=ringName,ringColor", null, as("estel"), 200).path("items"), "s09-block-car");
        assertThat(SnapshotSchemas.keys(sparse)).containsExactlyInAnyOrder("id", "ringName", "ringColor");
    }

    /**
     * Step 6 (CONVENCIONS_API §4): `GET /training-bookings/filter-values` and `GET /ring-blocks/filter-values` count each value
     * over the whole filtered set: of 1001 rows, the ring that only the last row in the list's order has (beyond the first
     * 1000) is listed with its count. An undeclared field is 400 INVALID_FILTER; MEMBER → 403; impersonation → 403.
     */
    @Test void CONVENCIONS_API_4_T_09_30_registerFilterValuesCountTheWholeFilteredSet() throws Exception {
        // training-bookings: default order startsAt desc, so the earliest row comes last.
        var start = local("2026-10-06T09:00").toEpochMilli(); var trainings = new ArrayList<Document>();
        for (int i = 0; i <= 1000; i++) {
            boolean last = i == 1000;
            trainings.add(new Document("_id", "s09-fv-" + i).append("clubId", CLUB).append("dogId", last ? "s09-d-blat" : "s09-d-rock")
                    .append("memberId", last ? "s09-m-pau" : "s09-m-maria").append("ringId", last ? CEN : MUN).append("slotId", "fv-" + i)
                    .append("startsAt", new Date(start - (last ? 1_000_000_000L : 60_000L * i))).append("endsAt", new Date(start - (last ? 1_000_000_000L : 60_000L * i) + 1_800_000L))
                    .append("state", "ACTIVE").append("origin", "APP").append("createdAt", new Date(start)));
        }
        mongo.getCollection("training_bookings").insertMany(trainings);
        var rings = call(GET, "/training-bookings/filter-values?field=ringId", null, as("admin"), 200);
        assertThat(rings.path("values")).extracting(v -> v.path("value").asText() + ":" + v.path("label").asText() + ":" + v.path("count").asLong())
                .containsExactly(MUN + ":Muntanya:1000", CEN + ":Central:1");
        SnapshotSchemas.assertConforms(rings, "FilterValues");
        var page = call(GET, "/training-bookings?size=1000&fields=ringId", null, as("admin"), 200);
        assertThat(page.path("totalItems").asLong()).isEqualTo(1001);
        assertThat(page.path("items")).extracting(item -> item.path("ringId").asText()).doesNotContain(CEN);
        var members = call(GET, "/training-bookings/filter-values?field=memberId&filter=ringId:eq:" + CEN, null, as("estel"), 200).path("values");
        assertThat(members).extracting(v -> v.path("label").asText() + ":" + v.path("count").asLong()).containsExactly("Pau Example:1");
        assertThat(code(call(GET, "/training-bookings/filter-values?field=memberName", null, as("admin"), 400))).isEqualTo("INVALID_FILTER");
        call(GET, "/training-bookings/filter-values?field=ringId", null, as("maria"), 403);
        call(GET, "/training-bookings/filter-values?field=ringId", null, impersonating("admin", "s09-m-maria"), 403);
        // ring-blocks: default order from asc, so the latest block comes last.
        var blocks = new ArrayList<Document>();
        for (int i = 0; i <= 1000; i++) {
            boolean last = i == 1000; var from = new Date(start + (last ? 1_000_000_000L : 60_000L * i));
            blocks.add(new Document("_id", "s09-fv-block-" + i).append("clubId", CLUB).append("ringId", last ? CAD : CAR).append("from", from)
                    .append("to", new Date(from.getTime() + 1_800_000L)).append("kind", "BLOCK").append("reason", last ? "OTHER" : "MAINTENANCE").append("state", "ACTIVE")
                    .append("version", 0L).append("createdAt", new Date(start)).append("createdByAccountId", "s09-estel"));
        }
        mongo.getCollection("ring_blocks").insertMany(blocks);
        var blockRings = call(GET, "/ring-blocks/filter-values?field=ringId", null, as("estel"), 200);
        assertThat(blockRings.path("values")).extracting(v -> v.path("value").asText() + ":" + v.path("label").asText() + ":" + v.path("count").asLong())
                .containsExactly(CAR + ":Carretera:1000", CAD + ":Cadells:1");
        SnapshotSchemas.assertConforms(blockRings, "FilterValues");
        var reasons = call(GET, "/ring-blocks/filter-values?field=reason&filter=ringId:eq:" + CAD, null, as("admin"), 200).path("values");
        assertThat(reasons).extracting(v -> v.path("value").asText() + ":" + v.path("count").asLong()).containsExactly("OTHER:1");
        assertThat(code(call(GET, "/ring-blocks/filter-values?field=note", null, as("admin"), 400))).isEqualTo("INVALID_FILTER");
        call(GET, "/ring-blocks/filter-values?field=ringId", null, as("maria"), 403);
    }
}
