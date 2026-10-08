package com.agilityhub.core.courses.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.convert.MongoConversionContext;
import org.springframework.data.mongodb.core.convert.MongoValueConverter;

/**
 * A9: preserve arbitrary JSON and rawMetadata keys, including dots and dollar signs, without map-key escaping.
 * The tree is copied node by node, never through extended JSON, so keys such as {@code $date} or {@code $oid} stay plain keys.
 */
public final class CourseJsonConverter implements MongoValueConverter<JsonNode, Object> {
    private static final JsonNodeFactory NODES = JsonNodeFactory.withExactBigDecimals(true);
    @Override public JsonNode read(Object value, MongoConversionContext context) { return toNode(value); }
    @Override public Object write(JsonNode value, MongoConversionContext context) { return toBson(value); }
    @Override public JsonNode readNull(MongoConversionContext context) { return NODES.nullNode(); }

    static Object toBson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) { return null; }
        if (node.isObject()) {
            var document = new Document();
            node.properties().forEach(entry -> document.put(entry.getKey(), toBson(entry.getValue())));
            return document;
        }
        if (node.isArray()) {
            var list = new ArrayList<Object>(node.size());
            node.forEach(item -> list.add(toBson(item)));
            return list;
        }
        if (node.isTextual()) { return node.textValue(); }
        if (node.isBoolean()) { return node.booleanValue(); }
        if (node.isInt() || node.isShort()) { return node.intValue(); }
        if (node.isLong()) { return node.longValue(); }
        if (node.isBigInteger() || node.isBigDecimal()) { return new Decimal128(node.decimalValue()); }
        if (node.isNumber()) { return node.doubleValue(); }
        throw new IllegalArgumentException("Unsupported course JSON node " + node.getNodeType());
    }

    static JsonNode toNode(Object value) {
        if (value == null) { return NODES.nullNode(); }
        if (value instanceof Map<?, ?> map) {
            ObjectNode object = NODES.objectNode();
            map.forEach((key, item) -> object.set(String.valueOf(key), toNode(item)));
            return object;
        }
        if (value instanceof List<?> list) {
            ArrayNode array = NODES.arrayNode(list.size());
            list.forEach(item -> array.add(toNode(item)));
            return array;
        }
        if (value instanceof String text) { return NODES.textNode(text); }
        if (value instanceof Boolean bool) { return NODES.booleanNode(bool); }
        if (value instanceof Integer number) { return NODES.numberNode(number); }
        if (value instanceof Long number) { return NODES.numberNode(number); }
        if (value instanceof Double number) { return NODES.numberNode(number); }
        if (value instanceof Decimal128 number) { return NODES.numberNode(number.bigDecimalValue()); }
        throw new IllegalStateException("Invalid stored course JSON value " + value.getClass().getSimpleName());
    }
}
