package com.agilityhub.core.platform.domain.audit;

import com.agilityhub.core.platform.application.audit.AuditChange;
import com.agilityhub.core.shared.domain.audit.AuditField;
import com.agilityhub.core.shared.domain.audit.Sensitive;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Detached snapshots retain raw comparisons only in memory, never in audit entries. */
public final class AuditDiff {
    private AuditDiff() { }

    public static Snapshot snapshot(Object source) {
        return new Snapshot(value(source, new IdentityHashMap<>()));
    }

    public static List<AuditChange> between(Snapshot before, Snapshot after) {
        var changes = new ArrayList<AuditChange>();
        compare("", before.value, after.value, changes);
        return List.copyOf(changes);
    }

    private static void compare(String path, Value before, Value after, List<AuditChange> changes) {
        if (Objects.equals(before.raw, after.raw)) { return; }
        if ((before.raw == null || before.raw instanceof Map) && (after.raw == null || after.raw instanceof Map)
                && (before.masked == null || before.masked instanceof Map) && (after.masked == null || after.masked instanceof Map)) {
            Map<?, ?> left = before.raw == null ? Map.of() : (Map<?, ?>) before.raw;
            Map<?, ?> right = after.raw == null ? Map.of() : (Map<?, ?>) after.raw;
            var keys = new LinkedHashSet<Object>(left.keySet());
            keys.addAll(right.keySet());
            for (Object key : keys) {
                String field = path.isEmpty() ? key.toString() : path + "." + key;
                compare(field, child(before, key), child(after, key), changes);
            }
        } else {
            changes.add(new AuditChange(path, before.masked, after.masked));
        }
    }

    private static Value child(Value parent, Object key) {
        if (parent.raw == null) { return new Value(null, null); }
        return new Value(((Map<?, ?>) parent.raw).get(key), ((Map<?, ?>) parent.masked).get(key));
    }

    private static Value value(Object source, IdentityHashMap<Object, Boolean> ancestors) {
        if (source == null || source instanceof String || source instanceof Number || source instanceof Boolean) {
            return new Value(source, source);
        }
        if (source instanceof Enum<?> || source instanceof TemporalAccessor || source instanceof UUID || source instanceof Character) {
            return new Value(source.toString(), source.toString());
        }
        if (ancestors.put(source, true) != null) { throw new IllegalArgumentException("Cyclic audit snapshot"); }
        try {
            if (source instanceof Iterable<?> iterable) {
                var values = new ArrayList<Value>();
                iterable.forEach(item -> values.add(value(item, ancestors)));
                return sequence(values);
            }
            if (source.getClass().isArray()) {
                var values = new ArrayList<Value>();
                for (int i = 0; i < Array.getLength(source); i++) { values.add(value(Array.get(source, i), ancestors)); }
                return sequence(values);
            }
            var raw = new LinkedHashMap<String, Object>();
            var masked = new LinkedHashMap<String, Object>();
            if (source instanceof Map<?, ?> map) {
                map.forEach((key, item) -> put(raw, masked, key.toString(), value(item, ancestors)));
            } else {
                for (Class<?> type = source.getClass(); type != Object.class; type = type.getSuperclass()) {
                    for (var field : type.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || !field.isAnnotationPresent(AuditField.class)) { continue; }
                        field.setAccessible(true);
                        Value captured = value(field.get(source), ancestors);
                        Sensitive sensitive = field.getAnnotation(Sensitive.class);
                        if (sensitive != null) {
                            captured = new Value(captured.raw, mask(captured.raw, sensitive.value()));
                        }
                        put(raw, masked, field.getName(), captured);
                    }
                }
            }
            return new Value(Collections.unmodifiableMap(raw), Collections.unmodifiableMap(masked));
        } catch (IllegalAccessException inaccessible) {
            throw new IllegalArgumentException("Cannot read annotated audit field", inaccessible);
        } finally {
            ancestors.remove(source);
        }
    }

    private static void put(Map<String, Object> raw, Map<String, Object> masked, String key, Value value) {
        raw.put(key, value.raw);
        masked.put(key, value.masked);
    }

    private static Value sequence(List<Value> values) {
        return new Value(values.stream().map(v -> v.raw).toList(), values.stream().map(v -> v.masked).toList());
    }

    private static Object mask(Object raw, Sensitive.Strategy strategy) {
        if (raw == null) { return null; }
        String text = raw.toString().replaceAll("\\s", "");
        return switch (strategy) {
            case HIDE -> "[ocult]";
            case MASK_IBAN -> text.length() <= 4 ? "[ocult]" : "···· ···· ···· ···· " + text.substring(text.length() - 4);
            case MASK_ID_DOCUMENT -> text.length() <= 4 ? "[ocult]"
                    : text.substring(0, 2) + "·".repeat(text.length() - 4) + text.substring(text.length() - 2);
        };
    }

    private record Value(Object raw, Object masked) { }

    /** Intentionally has no value accessors or toString exposing raw sensitive data. */
    public static final class Snapshot {
        private final Value value;
        private Snapshot(Value value) { this.value = value; }
    }
}
