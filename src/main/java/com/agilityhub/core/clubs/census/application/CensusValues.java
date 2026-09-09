package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;

/** Helpers for explicit application projections and validated JSON value objects. */
public final class CensusValues {
    private CensusValues() { }
    public static Map<String,Object> object(Object... entries) {
        var result = new LinkedHashMap<String,Object>();
        for (int i = 0; i < entries.length; i += 2) { if (entries[i + 1] != null) { result.put((String) entries[i], entries[i + 1]); } }
        return result;
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object raw) { return raw instanceof Map<?,?> ? (Map<String,Object>) raw : Map.of(); }
    public static List<Map<String,Object>> rows(Object raw) {
        if (raw == null) { return List.of(); }
        if (!(raw instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof Map<?,?>))) {
            throw invalid("items", "INVALID_VALUE");
        }
        return list.stream().map(CensusValues::map).toList();
    }
    public static Map<String,Object> select(Map<String,Object> source, String... fields) {
        var result = new LinkedHashMap<String,Object>(); for (String key : fields) { if (source.get(key) != null) { result.put(key, source.get(key)); } } return result;
    }
    public static String string(Object raw) { return raw == null ? null : raw.toString(); }
    public static Instant instant(Object raw) { return raw instanceof Date date ? date.toInstant() : raw == null ? null : Instant.parse(raw.toString()); }
    public static LocalDate date(Object raw) {
        return raw instanceof Date date ? date.toInstant().atZone(ZoneOffset.UTC).toLocalDate() : raw == null ? null : LocalDate.parse(raw.toString());
    }
    public static long number(Object raw) { return raw instanceof Number n ? n.longValue() : 0; }
    public static ApiException invalid(String field, String code) { return new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", List.of(Map.of("field", field, "code", code)))); }
    public static String text(Object raw, String field, int max, boolean required) {
        if (raw == null && !required) { return null; }
        if (!(raw instanceof String value) || (required && value.isBlank()) || value.length() > max) { throw invalid(field, "INVALID_VALUE"); }
        return value.strip();
    }
    public static void version(long actual, Object expected) {
        if (!(expected instanceof Number number)) { throw invalid("version", "REQUIRED"); }
        if (number.doubleValue() != number.longValue() || number.longValue() < 0) { throw invalid("version", "INVALID_VALUE"); }
        if (number.longValue() != actual) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }
    public static void allow(Map<String,Object> request, Set<String> fields) {
        for (String key : request.keySet()) { if (!fields.contains(key)) { throw invalid(key, "READ_ONLY"); } }
    }
}
