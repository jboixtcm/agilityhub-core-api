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

    /** Step 6 (CONVENCIONS_API §4): the three filter-values routes take `field`, `q` and `filter`, never `fields`, and answer `FilterValues`. */
    @Test void CONVENCIONS_API_4_theRegisterAndBookingListsPublishTheirFilterValues() {
        for (String list : List.of("bookings", "training-bookings", "ring-blocks")) {
            var operation = document.path("paths").path("/api/v1/" + list + "/filter-values").path("get");
            assertThat(operation.path("parameters").findValuesAsText("name")).as(list).containsExactly("field", "q", "filter");
            assertThat(operation.has("x-fields")).as(list).isFalse();
            assertThat(operation.path("x-filterable")).as(list).isEqualTo(document.path("paths").path("/api/v1/" + list).at("/get/x-filterable"));
            assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText()).as(list).isEqualTo("#/components/schemas/FilterValues");
            assertThat(operation.at("/responses/400/description").asText()).as(list).contains("INVALID_FILTER");
        }
    }

    /** Steps 13 and 14: the owner's first name, and the impersonated member's name on `/me`. */
    @Test void R_10_00_T_01_11_theOwnersFirstNameAndTheImpersonatedMembersName() {
        assertThat(texts(schema("OwnerSummary").path("required"))).contains("firstName");
        assertThat(texts(schema("Impersonation").path("required"))).containsExactlyInAnyOrder("actorName", "memberName");
    }
}
