package com.agilityhub.core.clubs.activities.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** E4-T06 step 1: the D7 columns of an `ActivityListItem` are the values of the activity's own view (`GET /activities/{id}`). */
public final class D7Columns {
    private D7Columns() { }
    static final List<String> SAME_AS_VIEW = List.of("title", "typeDisplay", "date", "startTime", "endTime", "allRings", "maxPlaces",
            "state", "type", "slug", "registrationTo");

    public static void assertSameAsView(JsonNode item, JsonNode view, String language) {
        String label = language + " " + item.path("title").asText() + " ";
        for (String field : SAME_AS_VIEW) { assertThat(item.get(field)).as(label + field).isNotNull().isEqualTo(view.get(field)); }
        // `location` is the free text of an activity away from the club, otherwise null.
        assertThat(item.get("location")).as(label + "location")
                .isEqualTo(view.at("/location/atClub").asBoolean() ? NullNode.getInstance() : view.at("/location/name"));
        assertThat(item.get("registrations")).as(label + "registrations").isEqualTo(view.get("counters"));
        assertThat(item.path("rings")).extracting(r -> r.path("id").asText()).as(label + "rings")
                .containsExactlyInAnyOrderElementsOf(view.path("rings").findValuesAsText("id"));
    }

    /** One D7 row as the mockup reads it: «{title} · {type}», date and hours, rings or place, registrations/places, state. */
    public static String row(JsonNode item) {
        var rings = new java.util.ArrayList<String>(); item.path("rings").forEach(r -> rings.add(r.path("name").asText()));
        java.util.Collections.sort(rings);
        return String.join(" · ", item.path("title").asText(), item.path("typeDisplay").asText(), item.path("date").asText(),
                item.path("startTime").asText() + "-" + item.path("endTime").asText(), "allRings=" + item.path("allRings").asText(),
                "rings=" + rings, "location=" + item.path("location").asText(),
                item.at("/registrations/active").asText() + "+" + item.at("/registrations/waiting").asText() + "/" + item.path("maxPlaces").asText(),
                item.path("state").asText());
    }
}
