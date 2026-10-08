package com.agilityhub.core.configuration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Fictional S16 examples shared by the HTTP, persistence and response contract tests. */
final class E9Fixtures {
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static JsonNode read(String name) throws Exception {
        try (var input = E9Fixtures.class.getResourceAsStream("/fixtures/" + name + ".json")) { return MAPPER.readTree(input); }
    }
    static ObjectNode response(String name) throws Exception {
        for (String group : List.of("course", "ring", "build")) {
            var found = read("contracts/e9-" + group + "-responses").get(name);
            if (found != null) { return (ObjectNode) found.deepCopy(); }
        }
        throw new IllegalArgumentException(name);
    }
    static <T> T document(Class<T> type, String id, String clubId) throws Exception {
        var json = type.getSimpleName().equals("Venue") ? MAPPER.createObjectNode()
                .put("slug", id).put("name", "Example venue").put("indoorOutdoor", "OUTDOOR").put("visibility", "MEMBERS_ONLY")
                .put("partnerStatus", "ACTIVE").put("defaultPaperSize", "A4") : response(type.getSimpleName());
        json.put("id", id); json.put("clubId", clubId);
        json.put("createdAt", "2026-10-08T08:00:00Z"); json.put("updatedAt", "2026-10-08T08:00:00Z");
        json.put("createdByAccountId", "e9-INSTRUCTOR"); json.put("updatedByAccountId", "e9-INSTRUCTOR");
        if (type.getSimpleName().equals("Course")) {
            json.put("ownerType", clubId == null ? "AGILITYHUB" : "CLUB"); json.put("visibility", "PUBLIC");
            json.set("name", MAPPER.createObjectNode().put("ca", "Recorregut de prova")); json.putArray("history");
        }
        if (type.getSimpleName().equals("Placement")) { json.set("warningsJson", json.remove("warnings")); }
        var names = new HashSet<String>(); for (var component : type.getRecordComponents()) { names.add(component.getName()); }
        json.retain(names);
        return MAPPER.treeToValue(json, type);
    }
}
