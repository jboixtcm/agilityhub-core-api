package com.agilityhub.core.platform.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Freeze JSON recursively so a cached configuration cannot be modified through nested values. */
public final class ImmutableValues {
    private ImmutableValues() { }
    public static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put((String) key, freeze(item)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) { return list.stream().map(ImmutableValues::freeze).toList(); }
        return value;
    }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> value) { return (Map<String, Object>) freeze(value); }
}
