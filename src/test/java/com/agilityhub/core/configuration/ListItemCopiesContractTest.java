package com.agilityhub.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * INC-12 (E5-T22's review, detail #8; E11-T02): four list items are hand-written copies of another type, so nothing but this
 * test keeps each pair in step. A shared property has the same schema in both (its description aside), and the properties
 * only one of them has are exactly the ones listed here: a new property on one side fails until it is added to the other or
 * listed with its reason.
 */
class ListItemCopiesContractTest {
    record Pair(String item, String copied, Set<String> onlyItem, Set<String> onlyCopied, String why) { }
    static final List<Pair> PAIRS = List.of(
            new Pair("ClassSessionListItem", "ClassSession", Set.of(), Set.of("attendanceStatus", "instructorNames", "ring"),
                    "the list reads the instructors and the ring from its own columns; the attendance status is the sheet's"),
            new Pair("RingBlockListItem", "RingBlock", Set.of("ringColor", "ringName"), Set.of(), "the register shows the ring of each row"),
            new Pair("MemberInstructorListItem", "MemberInstructorView", Set.of(), Set.of(), "the same instructor projection"),
            new Pair("ClassBookingItem", "BookingListItem", Set.of("displayState", "levelCode"), Set.of("classDescription", "ringColor", "ringId", "ringName"),
                    "a class's booking list shows the dog's level and state, the member's list the class and its ring"));

    static JsonNode schemas() throws Exception { return new ObjectMapper().readTree(Path.of("docs/openapi/openapi.json").toFile()).at("/components/schemas"); }
    static Set<String> names(JsonNode properties) { var names = new TreeSet<String>(); properties.fieldNames().forEachRemaining(names::add); return names; }
    static JsonNode withoutDescription(JsonNode schema) {
        var copy = schema.deepCopy();
        if (copy instanceof ObjectNode node) { node.remove("description"); }
        return copy;
    }

    @Test void INC_12_eachHandWrittenListItemStaysInStepWithTheTypeItCopies() throws Exception {
        var schemas = schemas();
        var problems = new ArrayList<String>();
        for (var pair : PAIRS) {
            var item = schemas.path(pair.item()).path("properties"); var copied = schemas.path(pair.copied()).path("properties");
            assertThat(item.isObject() && copied.isObject()).as(pair.item() + " and " + pair.copied() + " are published").isTrue();
            var onlyItem = new TreeSet<>(names(item)); onlyItem.removeAll(names(copied));
            var onlyCopied = new TreeSet<>(names(copied)); onlyCopied.removeAll(names(item));
            if (!onlyItem.equals(pair.onlyItem())) { problems.add(pair.item() + " only: " + onlyItem + " (listed " + pair.onlyItem() + ")"); }
            if (!onlyCopied.equals(pair.onlyCopied())) { problems.add(pair.copied() + " only: " + onlyCopied + " (listed " + pair.onlyCopied() + ")"); }
            for (String name : names(item)) {
                if (copied.has(name) && !withoutDescription(item.path(name)).equals(withoutDescription(copied.path(name)))) {
                    problems.add(pair.item() + "." + name + " " + item.path(name) + " differs from " + pair.copied() + "." + name + " " + copied.path(name));
                }
            }
        }
        assertThat(problems).isEmpty();
    }
}
