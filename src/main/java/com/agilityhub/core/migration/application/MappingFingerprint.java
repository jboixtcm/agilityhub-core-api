package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.domain.MappingConfig;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.security.*;
import java.util.*;

/** Binds report recovery to the actual mapping, including custom version-2 mappings. */
final class MappingFingerprint {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> SETS=Set.of("unresolvedPlans","withoutPlan","familyPlans","instructorPlans","unresolvedLevels");
    private MappingFingerprint() { }
    static String of(MappingConfig mapping) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(sorted(JSON.valueToTree(mapping),"")))); }
        catch (java.io.IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonNode sorted(JsonNode node,String name) {
        if (node.isObject()) {
            var result=JSON.createObjectNode(); var fields=new TreeMap<String,JsonNode>(); node.properties().forEach(entry->fields.put(entry.getKey(),entry.getValue()));
            fields.forEach((key,value)->result.set(key,sorted(value,key))); return result;
        }
        if (node.isArray()) {
            var values=new ArrayList<JsonNode>(); node.forEach(value->values.add(sorted(value,"")));
            if (SETS.contains(name)) { values.sort(Comparator.comparing(JsonNode::asText)); }
            var result=JSON.createArrayNode(); values.forEach(result::add); return result;
        }
        return node;
    }
}
