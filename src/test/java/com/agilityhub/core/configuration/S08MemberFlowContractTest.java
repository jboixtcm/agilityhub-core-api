package com.agilityhub.core.configuration;

import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E5-T25: the S08 member flow's contract gaps found by the web's E5-W01, read from the committed snapshot (which
 * {@code OpenApiSnapshotTest} keeps equal to the generated document). The behaviour behind each field is in
 * {@code MemberFlowContractIT}.
 */
class S08MemberFlowContractTest {
    static JsonNode document;
    @BeforeAll static void snapshot() throws Exception { document = new ObjectMapper().readTree(Path.of("docs/openapi/openapi.json").toFile()); }
    static JsonNode schema(String name) { return document.at("/components/schemas/" + name); }
    static JsonNode operation(String path, String method) { return document.path("paths").path(path).path(method); }
    static List<String> texts(JsonNode node) {
        var values = new ArrayList<String>();
        if (node.isArray()) { node.forEach(value -> values.add(value.asText())); } else if (!node.isMissingNode()) { values.add(node.asText()); }
        return values;
    }
    /** The error codes a response publishes: its description lists them, comma-separated (`@ContractErrors`). */
    static List<String> codes(JsonNode response) { return Arrays.stream(response.path("description").asText().split(",")).map(String::trim).toList(); }

    /** Step 1 (S08 §2 row 07): every `Booking` carries the booked dog as a `HoldDog`, like `SeatHoldResponse.dog`. */
    @Test void S08_07_T_08_15_aBookingCarriesTheBookedDog() {
        assertThat(texts(schema("Booking").path("required"))).contains("dog");
        assertThat(schema("Booking").at("/properties/dog/$ref").asText()).isEqualTo("#/components/schemas/HoldDog");
        assertThat(texts(schema("HoldDog").path("required"))).containsExactlyInAnyOrder("id", "name", "sex");
        assertThat(texts(schema("HoldDog").at("/properties/sex/enum"))).containsExactlyInAnyOrder("MALE", "FEMALE");
    }

    /** Step 2 (R-08-10): the threshold and the in-time deadline, computed by the api, on every `Booking`. */
    @Test void R_08_10_T_08_42_aBookingCarriesTheThresholdAndTheInTimeDeadline() {
        assertThat(texts(schema("Booking").path("required"))).contains("lateCancelThresholdMinutes", "cancellableInTimeUntil");
        var threshold = schema("Booking").at("/properties/lateCancelThresholdMinutes");
        assertThat(threshold.path("type").asText()).isEqualTo("integer");
        assertThat(threshold.path("description").asText()).contains("bookings.lateCancelThresholdMinutes");
        var until = schema("Booking").at("/properties/cancellableInTimeUntil");
        assertThat(until.path("type").asText()).isEqualTo("string");
        assertThat(until.path("format").asText()).isEqualTo("date-time");
        assertThat(until.path("description").asText()).contains("now <= this instant");
    }

    /** Step 3 (S08 §2 row 07, waiting-list detail): `WaitlistEntry.dog`; `dogName` stays. */
    @Test void S08_07_T_08_19_aWaitlistEntryCarriesTheWaitingDog() {
        assertThat(texts(schema("WaitlistEntry").path("required"))).contains("dog");
        assertThat(schema("WaitlistEntry").at("/properties/dog/$ref").asText()).isEqualTo("#/components/schemas/HoldDog");
        assertThat(schema("WaitlistEntry").at("/properties/dogName").isMissingNode()).isFalse();
    }

    /** Step 4 (S08 §2 row 03, S07 §2): `ringColor` and `activityId` on 03's rows, nullable. */
    @Test void S08_03_T_08_12_T_07_16_homeRowsCarryTheRingColourAndTheActivity() {
        for (String field : List.of("ringColor", "activityId")) {
            assertThat(texts(schema("ReservationRow").at("/properties/" + field + "/type"))).as(field).containsExactlyInAnyOrder("string", "null");
            assertThat(texts(schema("ReservationRow").path("required"))).as(field).doesNotContain(field);
        }
        assertThat(schema("ReservationRow").at("/properties/activityId/description").asText()).contains("ACTIVITY rows only");
    }

    /** Step 5 (S08 §2 row 07): `BookedBy.self`; `displayName` and `viaClub` are unchanged. */
    @Test void S08_07_T_08_27_bookedBySaysWhetherTheReaderMadeTheBooking() {
        assertThat(texts(schema("BookedBy").path("required"))).containsExactlyInAnyOrder("displayName", "viaClub", "self");
        assertThat(schema("BookedBy").at("/properties/self/type").asText()).isEqualTo("boolean");
        assertThat(schema("BookedBy").at("/properties/viaClub/type").asText()).isEqualTo("boolean");
        assertThat(schema("BookedBy").at("/properties/displayName/type").asText()).isEqualTo("string");
    }

    /** Step 6 (R-08-08, amended 26-09): the key is one client UUID per body, never the `seatHoldId`. */
    @Test void R_08_08_T_08_16_theIdempotencyKeyOfABookingIsOneUuidPerBody() {
        var confirm = operation("/api/v1/bookings", "post");
        assertThat(confirm.path("description").asText()).doesNotContain("seatHoldId,").doesNotContain("= seatHoldId")
                .contains("one client UUID per request body").contains("a different body").contains("IDEMPOTENCY_KEY_REUSED");
        var key = new ArrayList<JsonNode>(); confirm.path("parameters").forEach(p -> { if (p.path("name").asText().equals("Idempotency-Key")) { key.add(p); } });
        assertThat(key).singleElement().satisfies(header -> {
            assertThat(header.path("in").asText()).isEqualTo("header");
            assertThat(header.path("required").asBoolean()).isTrue();
            assertThat(header.at("/schema/format").asText()).isEqualTo("uuid");
            assertThat(header.path("description").asText()).contains("One client UUID per request body").doesNotContain("seatHoldId");
        });
        assertThat(codes(confirm.at("/responses/409"))).contains("IDEMPOTENCY_KEY_REUSED");
    }

    /** Step 7 (`CATALEG_ERRORS.md` and R-08-10, amended 26-09): `BOOKING_NOT_CANCELLABLE` is published as 422, never as 409. */
    @Test void R_08_10_T_08_18_bookingNotCancellableIsPublishedAs422Only() {
        var cancellation = operation("/api/v1/bookings/{id}/cancellation", "post").path("responses");
        assertThat(codes(cancellation.path("422"))).contains("BOOKING_NOT_CANCELLABLE");
        assertThat(codes(cancellation.path("409"))).doesNotContain("BOOKING_NOT_CANCELLABLE");
        var statuses = new ArrayList<String>();
        document.path("paths").forEach(item -> item.forEach(op -> op.path("responses").fields().forEachRemaining(response -> {
            if (codes(response.getValue()).contains("BOOKING_NOT_CANCELLABLE")) { statuses.add(response.getKey()); }
        })));
        assertThat(statuses).isNotEmpty().containsOnly("422");
        assertThat(ErrorCode.BOOKING_NOT_CANCELLABLE.httpStatus()).isEqualTo(422);
    }
}
