package com.agilityhub.core.platform.domain.audit;

import java.util.*;

/** Defense in depth for historical/untyped details, including values inside whole-object diffs. */
public final class AuditReadMasker {
    private AuditReadMasker() { }
    public static Map<String, Object> sanitize(Map<String, Object> row) {
        var result = new LinkedHashMap<>(row);
        for (String field : List.of("details", "changes")) {
            if (row.containsKey(field)) { result.put(field, mask(field, row.get(field))); }
        }
        return result;
    }
    private static Object mask(String path, Object value) {
        if ("[ocult]".equals(value) || value == null || value instanceof com.fasterxml.jackson.databind.node.NullNode) { return value; }
        String normalized = path.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (normalized.contains("password") || normalized.contains("secret") || normalized.contains("token")
                || normalized.contains("apikey") || normalized.contains("privatekey") || normalized.endsWith("chip")) { return "[ocult]"; }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            String changedPath = map.get("path") instanceof String field ? field : null;
            map.forEach((key, item) -> result.put(key.toString(), mask(
                    changedPath != null && Set.of("before", "after").contains(key.toString())
                            ? changedPath : path + "." + key, item)));
            return result;
        }
        if (value instanceof List<?> list) { return list.stream().map(item -> mask(path, item)).toList(); }
        String text = value.toString().replaceAll("\\s", "");
        if (normalized.endsWith("iban")) {
            return text.length() <= 4 ? "[ocult]" : "···· ···· ···· ···· " + text.substring(text.length() - 4);
        }
        if (normalized.endsWith("iddocumentnumber") || normalized.endsWith("holdertaxid") || normalized.endsWith("taxid")) {
            return text.length() <= 4 ? "[ocult]" : text.substring(0, 2) + "·".repeat(text.length() - 4) + text.substring(text.length() - 2);
        }
        return value;
    }
}
