package com.agilityhub.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E5-T29: the contract the web's E5-W05 adopts, read from the committed snapshot (which {@code OpenApiSnapshotTest} keeps equal to
 * the generated document). The behaviour behind each field is in {@code BackOfficeContractIT}, {@code TrainingRegisterContractIT},
 * {@code CensusIT}, {@code ImpersonationHandoffIT} and {@code IdentityCoreIT}.
 */
class E5BackOfficeContractTest {
    static JsonNode document;
    @BeforeAll static void snapshot() throws Exception { document = new ObjectMapper().readTree(Path.of("docs/openapi/openapi.json").toFile()); }
    static JsonNode schema(String name) { return document.at("/components/schemas/" + name); }
    static List<String> texts(JsonNode node) { return S08MemberFlowContractTest.texts(node); }
    static List<String> nullable(String schema, String property) { return texts(schema(schema).at("/properties/" + property + "/type")); }

    /** Steps 1, 2, 4 and 9 (S08 §6, S10 R-10-00): the registrants, the staff waitlist, D10's rows and 07's ring colour. */
    @Test void S08_6_R_10_00_theS08BackOfficeFormsCarryTheirNewFields() {
        assertThat(texts(schema("ClassBookingItem").path("required"))).contains("displayState").doesNotContain("levelCode");
        assertThat(schema("ClassBookingItem").at("/properties/displayState/enum")).isEqualTo(schema("Booking").at("/properties/displayState/enum"));
        assertThat(nullable("ClassBookingItem", "levelCode")).containsExactlyInAnyOrder("string", "null");
        for (String field : List.of("memberFirstName", "handlerName")) {
            assertThat(nullable("WaitlistEntry", field)).as(field).containsExactlyInAnyOrder("string", "null");
            assertThat(texts(schema("WaitlistEntry").path("required"))).as(field).doesNotContain(field);
        }
        assertThat(texts(schema("BookingListItem").path("required"))).containsExactly("id");
        for (String field : List.of("ringId", "ringName", "ringColor")) { assertThat(nullable("BookingListItem", field)).as(field).containsExactlyInAnyOrder("string", "null"); }
        assertThat(texts(document.at("/paths/~1api~1v1~1bookings/get/x-fields"))).contains("classDescription", "ringId", "ringName", "ringColor");
        assertThat(nullable("BookingClassSession", "ringColor")).containsExactlyInAnyOrder("string", "null");
        assertThat(schema("BookingLimitReachedDetails").at("/properties/nextBookableAt/description").asText()).contains("end of the current one", "NEXT");
    }

    /** Steps 3 and 5 (S09): the register's end, member number and ring. */
    @Test void S09_2_theRegisterFormsCarryTheirNewFields() {
        assertThat(texts(document.at("/paths/~1api~1v1~1training-bookings/get/x-fields"))).contains("endsAt", "endsAtLocal", "memberNumber");
        assertThat(document.at("/paths/~1api~1v1~1training-bookings~1export/get/x-fields")).isEqualTo(document.at("/paths/~1api~1v1~1training-bookings/get/x-fields"));
        assertThat(nullable("TrainingBookingListItem", "memberNumber")).containsExactlyInAnyOrder("integer", "null");
        assertThat(texts(document.at("/paths/~1api~1v1~1ring-blocks/get/x-fields"))).contains("ringName", "ringColor");
        assertThat(nullable("RingBlockListItem", "ringColor")).containsExactlyInAnyOrder("string", "null");
    }

    /**
     * Step 6 (CONVENCIONS_API §4): the three filter-values routes take `field` and `filter`, never `fields`, and answer `FilterValues`.
     * Round 2 (E75): `q` too where the list searches (the register and the blocks), not on the bookings.
     */
    @Test void CONVENCIONS_API_4_theRegisterAndBookingListsPublishTheirFilterValues() {
        for (String list : List.of("bookings", "training-bookings", "ring-blocks")) {
            var operation = document.path("paths").path("/api/v1/" + list + "/filter-values").path("get");
            assertThat(operation.path("parameters").findValuesAsText("name")).as(list)
                    .containsExactlyElementsOf(list.equals("bookings") ? List.of("field", "filter") : List.of("field", "q", "filter"));
            assertThat(operation.has("x-fields")).as(list).isFalse();
            assertThat(operation.path("x-filterable")).as(list).isEqualTo(document.path("paths").path("/api/v1/" + list).at("/get/x-filterable"));
            assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText()).as(list).isEqualTo("#/components/schemas/FilterValues");
            assertThat(operation.at("/responses/400/description").asText()).as(list).contains("INVALID_FILTER");
        }
    }

    /**
     * Round 2 (ruling E75, CONVENCIONS_API §4 as amended 30-09): `q` is declared exactly on the operations whose list searches (each
     * list's searchable paths, read in its `ListDefinition` or its catalog query). The lists without search do not declare it:
     * the bookings and their filter values, the class sessions, the weeks, the attendances, a process's runs, and the notification
     * log with its filter values and export. `BackOfficeContractIT` checks that they answer a non-blank `q` with 400 INVALID_FILTER,
     * and `TrainingRegisterContractIT` that the register and the blocks search.
     */
    @Test void CONVENCIONS_API_4_qIsDeclaredOnlyOnTheListsThatSearch() {
        var declared = new java.util.TreeSet<String>();
        document.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(operation -> {
            if (operation.getValue().path("parameters").findValuesAsText("name").contains("q")) { declared.add(operation.getKey().toUpperCase() + " " + path.getKey()); }
        }));
        assertThat(declared).containsExactlyInAnyOrder(
                // The census (S03), the audit (S14), the activities (S07), the register and the blocks (S09 §2), the FAQ categories (S05).
                "GET /api/v1/members", "GET /api/v1/members/export", "POST /api/v1/members/export", "GET /api/v1/members/filter-values",
                "GET /api/v1/dogs", "GET /api/v1/dogs/export", "POST /api/v1/dogs/export", "GET /api/v1/dogs/filter-values",
                "GET /api/v1/audit-entries", "GET /api/v1/audit-entries/export", "POST /api/v1/audit-entries/export", "GET /api/v1/audit-entries/filter-values",
                "GET /api/v1/members/{id}/audit-entries",
                "GET /api/v1/activities", "GET /api/v1/activities/export", "GET /api/v1/activities/filter-values", "GET /api/v1/activities/{id}/registrations",
                "GET /api/v1/activity-registrations/export",
                "GET /api/v1/training-bookings", "GET /api/v1/training-bookings/export", "GET /api/v1/training-bookings/filter-values",
                "GET /api/v1/ring-blocks", "GET /api/v1/ring-blocks/filter-values",
                "GET /api/v1/faq-entries/filter-values",
                // D14 and its filter values (E6-T06, ruling E75).
                "GET /api/v1/followup", "GET /api/v1/followup/filter-values",
                // Contract-only routes: their implementation (and search) is deferred.
                "GET /api/v1/invoices/export", "GET /api/v1/platform/audit-entries", "GET /api/v1/platform/erasure-requests", "GET /api/v1/platform/security-events");
        for (String without : List.of("/bookings", "/bookings/filter-values", "/class-sessions", "/weeks", "/attendances", "/jobs/{name}/runs",
                "/notifications", "/notifications/filter-values", "/notifications/export")) {
            assertThat(document.path("paths").has("/api/v1" + without)).as(without).isTrue();
            assertThat(declared).as(without).doesNotContain("GET /api/v1" + without);
        }
    }

    /** Round 2 (review #2): the register's export offers the end and the member's number as columns, not among the defaults. */
    @Test void CONVENCIONS_API_4_theRegisterExportPublishesItsNewColumns() {
        for (String path : List.of("/api/v1/training-bookings", "/api/v1/training-bookings/export")) {
            var columns = document.path("paths").path(path).at("/get/x-columns");
            assertThat(columns.findValuesAsText("key")).as(path).contains("endsAt", "endsAtLocal", "memberNumber");
            for (var column : columns) {
                if (List.of("endsAt", "endsAtLocal", "memberNumber").contains(column.path("key").asText())) { assertThat(column.path("defaultVisible").asBoolean()).isFalse(); }
            }
        }
    }

    /** Steps 13 and 14: the owner's first name, and the impersonated member's name on `/me`. */
    @Test void R_10_00_T_01_11_theOwnersFirstNameAndTheImpersonatedMembersName() {
        assertThat(texts(schema("OwnerSummary").path("required"))).contains("firstName");
        assertThat(texts(schema("Impersonation").path("required"))).containsExactlyInAnyOrder("actorName", "memberName");
    }
}
