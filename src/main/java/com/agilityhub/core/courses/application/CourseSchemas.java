package com.agilityhub.core.courses.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** R-16-01 / E84: validate the client's geometry, never reimplement it. Both schemas are local draft-07 resources. */
@Component
public class CourseSchemas {
    static final int MAX_BYTES = 256 * 1024;
    private final JsonSchema courseData;
    private final JsonSchema export;
    private final ObjectMapper mapper;
    public CourseSchemas(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        courseData = load("course-data.v1.schema.json");
        export = load("build-session-export.v1.schema.json");
    }
    private JsonSchema load(String name) throws IOException {
        try (var input = new ClassPathResource("schemas/" + name).getInputStream()) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(input);
        }
    }
    public void validateCourseData(Integer schemaVersion, JsonNode normalizedJson) {
        if (!Integer.valueOf(1).equals(schemaVersion)) { throw new ApiException(ErrorCode.SCHEMA_VERSION_UNSUPPORTED); }
        if (normalizedJson == null || normalizedJson.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ApiException(ErrorCode.COURSE_MODEL_INVALID, Map.of("schemaErrors", List.of(Map.of("path", "$", "message", "Required JSON object, at most 256 KiB"))));
        }
        validate(courseData, normalizedJson);
        var unstorable = new java.util.ArrayList<Map<String, String>>();
        numbers(normalizedJson, "$", unstorable);
        if (!unstorable.isEmpty()) { throw new ApiException(ErrorCode.COURSE_MODEL_INVALID, Map.of("schemaErrors", unstorable)); }
    }
    /** E98: a number no double can hold either (`1e400`) cannot be stored (`CourseJsonConverter.decimal`): a client error, never a 500. */
    private static void numbers(JsonNode node, String path, List<Map<String, String>> errors) {
        if (node.isNumber() && !com.agilityhub.core.courses.persistence.CourseJsonConverter.storable(node)) {
            errors.add(Map.of("path", path, "message", "Number outside the storable range"));
        }
        if (node.isObject()) { node.properties().forEach(entry -> numbers(entry.getValue(), path + "." + entry.getKey(), errors)); }
        if (node.isArray()) { for (int i = 0; i < node.size(); i++) { numbers(node.get(i), path + "[" + i + "]", errors); } }
    }
    /**
     * E9-T04's self-check of `BuildSessionExportV1`. The export is built by the server from stored documents, so a result the schema
     * refuses is the server's fault (E98): `500 INTERNAL_ERROR`, its schema errors in the logged cause, nothing for the client.
     */
    public void validateExport(JsonNode json) {
        var errors = errors(export, json == null ? mapper.nullNode() : json);
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, new IllegalStateException("BuildSessionExportV1 self-check failed: " + errors));
        }
    }
    private void validate(JsonSchema schema, JsonNode json) {
        var errors = errors(schema, json);
        if (!errors.isEmpty()) { throw new ApiException(ErrorCode.COURSE_MODEL_INVALID, Map.of("schemaErrors", errors)); }
    }
    private static List<Map<String, String>> errors(JsonSchema schema, JsonNode json) {
        return schema.validate(json).stream().map(error -> Map.of("path", error.getInstanceLocation().toString(), "message", error.getMessage()))
                .sorted(java.util.Comparator.comparing(value -> value.get("path") + value.get("message"))).toList();
    }
}
