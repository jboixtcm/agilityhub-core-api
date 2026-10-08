package com.agilityhub.core.courses.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.springframework.data.mongodb.core.convert.MongoConversionContext;
import org.springframework.data.mongodb.core.convert.MongoValueConverter;

/** A9: preserve arbitrary JSON and rawMetadata keys, including dots and dollar signs, without map-key escaping. */
public final class CourseJsonConverter implements MongoValueConverter<JsonNode, Object> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @Override public JsonNode read(Object value, MongoConversionContext context) {
        try { return MAPPER.readTree(new Document("value", value).toJson()).get("value"); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("Invalid stored course JSON", invalid); }
    }
    @Override public Object write(JsonNode value, MongoConversionContext context) {
        return Document.parse("{\"value\":" + value + "}").get("value");
    }
    @Override public JsonNode readNull(MongoConversionContext context) { return MAPPER.nullNode(); }
}
