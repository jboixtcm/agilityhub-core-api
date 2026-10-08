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
    }
    public void validateExport(JsonNode json) { validate(export, json == null ? mapper.nullNode() : json); }
    private void validate(JsonSchema schema, JsonNode json) {
        var errors = schema.validate(json).stream().map(error -> Map.of("path", error.getInstanceLocation().toString(), "message", error.getMessage()))
                .sorted(java.util.Comparator.comparing(value -> value.get("path") + value.get("message"))).toList();
        if (!errors.isEmpty()) { throw new ApiException(ErrorCode.COURSE_MODEL_INVALID, Map.of("schemaErrors", errors)); }
    }
}
