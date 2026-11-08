package com.agilityhub.core.clubs.census.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A missing FCAG category starts with RSCE's value; a stored category remains independent. */
public final class LicenseCategories {
    private LicenseCategories() { }

    public static List<Map<String, Object>> initialize(List<Map<String, Object>> licenses) {
        String source = licenses.stream()
                .filter(row -> "RSCE".equalsIgnoreCase(String.valueOf(row.get("organisation"))))
                .map(row -> row.get("category"))
                .filter(value -> value instanceof String text && !text.isBlank())
                .map(String.class::cast).findFirst().orElse(null);
        if (source == null) return licenses;
        return licenses.stream().map(row -> {
            if (!"FCAG".equalsIgnoreCase(String.valueOf(row.get("organisation"))) || row.get("category") != null) return row;
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put("category", source);
            return copy;
        }).toList();
    }
}
