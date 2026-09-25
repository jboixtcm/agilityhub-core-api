package com.agilityhub.core.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;

/**
 * E4-T06: a real response checked against a schema of the committed OpenAPI snapshot (`docs/openapi/openapi.json`) with a
 * JSON Schema 2020-12 validator, so a test never passes on a shape the contract does not publish.
 */
public final class SnapshotSchemas {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private SnapshotSchemas() { }

    public static JsonNode components() {
        try { return MAPPER.readTree(Path.of("docs/openapi/openapi.json").toFile()).path("components"); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    public static JsonNode schema(String name) { return components().path("schemas").path(name); }
    /** The messages of `value` against `#/components/schemas/{name}`; empty when it conforms. */
    public static List<String> violations(JsonNode value, String name) {
        var root = MAPPER.createObjectNode().put("$ref", "#/components/schemas/" + name);
        root.set("components", components());
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root).validate(value).stream().map(Object::toString).toList();
    }
    /** The documented property names: a response sends exactly these keys (with `null` where the schema allows it). */
    public static Set<String> properties(String name) {
        var names = new TreeSet<String>(); schema(name).path("properties").fieldNames().forEachRemaining(names::add); return names;
    }
    public static Set<String> required(String name) {
        var names = new TreeSet<String>(); schema(name).path("required").forEach(n -> names.add(n.asText())); return names;
    }
    public static Set<String> keys(JsonNode value) {
        var names = new TreeSet<String>(); value.fieldNames().forEachRemaining(names::add); return names;
    }
    /** A response object that conforms to the schema and sends every documented property. */
    public static void assertConforms(JsonNode value, String name) {
        org.assertj.core.api.Assertions.assertThat(violations(value, name)).as(name + " " + value).isEmpty();
        org.assertj.core.api.Assertions.assertThat(keys(value)).as(name + " keys").isEqualTo(properties(name));
    }
}
