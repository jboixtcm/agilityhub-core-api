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

    static List<String> ids(JsonNode page) { var ids = new ArrayList<String>(); page.path("items").forEach(item -> ids.add(item.path("id").asText())); return ids; }
    /** An inline export of the register as ADMIN, in English. */
    byte[] export(String format, String query) throws Exception {
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/training-bookings/export?format=" + format + query)
                .header("Host", HOST).header("Accept-Language", "en").with(as("admin"))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response.getContentAsByteArray();
    }
    /** The rows of an XLSX export, header first. */
    static List<List<String>> sheet(byte[] file) throws Exception {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(file))) {
            var rows = new ArrayList<List<String>>();
            for (var row : workbook.getSheetAt(0)) {
                var cells = new ArrayList<String>(); for (int i = 0; i < row.getLastCellNum(); i++) { cells.add(row.getCell(i).getStringCellValue()); }
                rows.add(cells);
            }
            return rows;
        }
    }
    static String pdf(byte[] file) throws Exception {
        try (var document = org.apache.pdfbox.Loader.loadPDF(file)) { return new org.apache.pdfbox.text.PDFTextStripper().getText(document); }
    }

    /**
     * Round 2 (review #2, CONVENCIONS_API §4): the register's export has the columns its `x-fields` publish. `endsAt`, `endsAtLocal`
     * and `memberNumber` are columns of the xlsx and the pdf, `fields` with only them is a valid selection, and the default columns
     * stay the six of before. The headers are the columns' names in the reader's language.
     */
    @Test void CONVENCIONS_API_4_T_09_30_theRegisterExportHasTheEndAndTheMemberNumberColumns() throws Exception {
        var rock = book(as("maria"), "s09-d-rock", "2026-10-06T09:30", CEN, 201);
        long number = mongo.findById("s09-m-maria", Document.class, "members").get("memberNumber", Number.class).longValue();
        String only = "&filter=id:in:" + rock.path("id").asText();
        var rows = sheet(export("xlsx", "&fields=endsAt,endsAtLocal,memberNumber" + only));
        assertThat(rows.getFirst()).containsExactly("End date", "End", "Number");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("06/10/2026", "10:00", Long.toString(number));
        assertThat(sheet(export("xlsx", "&columns=memberName,memberNumber,endsAtLocal" + only)).get(1)).containsExactly("Maria Example", Long.toString(number), "10:00");
        assertThat(pdf(export("pdf", "&fields=endsAtLocal,memberNumber" + only))).contains("End: 10:00", "Number: " + number);
        assertThat(sheet(export("xlsx", only)).getFirst()).as("the default columns").containsExactly("Date", "Start", "Ring", "Member", "Dog", "Status");
    }

    /**
     * Round 2 (ruling E75; CONVENCIONS_API §4, S09 §2): `q` on the register matches the member's full name (first name and both last
     * names), the dog's name and the ring's name, in any case; its filter values and its export search the same way.
     */
    @Test void CONVENCIONS_API_4_T_09_30_theRegisterSearchesTheMemberTheDogAndTheRing() throws Exception {
        String rock = book(as("maria"), "s09-d-rock", "2026-10-06T09:30", CEN, 201).path("id").asText();
        String blat = book(as("pau"), "s09-d-blat", "2026-10-05T09:00", MUN, 201).path("id").asText();
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s09-m-maria")), new Update().set("lastName2", "Serrallonga"), "members");
        var searches = new LinkedHashMap<String, List<String>>();
        searches.put("serrallonga", List.of(rock)); searches.put("MARIA", List.of(rock)); searches.put("pau", List.of(blat));
        searches.put("rock", List.of(rock)); searches.put("Bla", List.of(blat)); searches.put("centr", List.of(rock)); searches.put("muntanya", List.of(blat));
        searches.put("example", List.of(rock, blat)); searches.put("nowhere", List.of());
        for (var search : searches.entrySet()) {
            for (String reader : List.of("admin", "estel")) {
                assertThat(ids(call(GET, "/training-bookings?q=" + search.getKey(), null, as(reader), 200))).as(reader + " q=" + search.getKey())
                        .containsExactlyInAnyOrderElementsOf(search.getValue());
            }
        }
        assertThat(call(GET, "/training-bookings/filter-values?field=ringId&q=blat", null, as("admin"), 200).path("values"))
                .extracting(v -> v.path("value").asText() + ":" + v.path("count").asLong()).containsExactly(MUN + ":1");
        var rows = sheet(export("xlsx", "&columns=dogName&q=serrallonga"));
        assertThat(rows).hasSize(2); assertThat(rows.get(1)).containsExactly("Rock");
    }

    /**
     * Round 2 (ruling E75; CONVENCIONS_API §4, S09 §2): `q` on the ring blocks matches the ring's name (a deactivated ring's too) and
     * the block's note. A member never reads a note, so a member's `q` never matches one; the filter values search the same way.
     */
    @Test void CONVENCIONS_API_4_T_09_27_theBlocksSearchTheRingAndTheNote() throws Exception {
        block("s09-block-car", CAR, "2026-10-06T16:00", "2026-10-06T18:00", "BLOCK", "MAINTENANCE", "Reg de la pista", null);
        block("s09-block-pet", PET, "2026-10-07T16:00", "2026-10-07T17:00", "BLOCK", "MAINTENANCE", null, null);
        block("s09-block-cen", CEN, "2026-10-08T16:00", "2026-10-08T17:00", "BLOCK", "PRIVATE_CLASS", "Classe particular", null);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(PET)), new Update().set("active", false), "rings");
        var searches = new LinkedHashMap<String, List<String>>();
        searches.put("CARRET", List.of("s09-block-car")); searches.put("petita", List.of("s09-block-pet")); searches.put("REG", List.of("s09-block-car"));
        searches.put("particular", List.of("s09-block-cen")); searches.put("nowhere", List.of());
        for (var search : searches.entrySet()) {
            for (String reader : List.of("admin", "estel")) {
                assertThat(ids(call(GET, "/ring-blocks?q=" + search.getKey(), null, as(reader), 200))).as(reader + " q=" + search.getKey())
                        .containsExactlyInAnyOrderElementsOf(search.getValue());
            }
        }
        assertThat(ids(call(GET, "/ring-blocks?q=carret", null, as("maria"), 200))).containsExactly("s09-block-car");
        assertThat(ids(call(GET, "/ring-blocks?q=particular", null, as("maria"), 200))).as("a member's q never matches a note").isEmpty();
        assertThat(call(GET, "/ring-blocks/filter-values?field=reason&q=pista", null, as("estel"), 200).path("values"))
                .extracting(v -> v.path("value").asText() + ":" + v.path("count").asLong()).containsExactly("MAINTENANCE:1");
    }
}
