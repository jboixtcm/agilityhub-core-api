package com.agilityhub.core.clubs.messaging.application;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

/**
 * The `diff` of `MessageTemplateChanged{id, diff}` and `NotificationPreferencesChanged{memberId, diff, byAccountId}` (S11 §7),
 * as the catalogs' events write theirs: every changed top-level field of two snapshots, `{field: {before, after}}`, with the
 * whole values (`title` → `{before: {ca, es}, after: {ca, es}}`); `null` where a side has none. The outbox stores the payload
 * in Mongo, whose keys cannot hold the dotted paths of the audit (`changes[].path = title.ca`).
 */
public final class TemplateDiff {
    private TemplateDiff() { }

    public static Map<String, Object> between(Map<String, ?> before, Map<String, ?> after) {
        Map<String, ?> left = before == null ? Map.of() : before, right = after == null ? Map.of() : after;
        var keys = new LinkedHashSet<String>(left.keySet()); keys.addAll(right.keySet());
        var diff = new LinkedHashMap<String, Object>();
        for (String key : keys) {
            Object from = left.get(key), to = right.get(key);
            if (!Objects.equals(from, to)) {
                var change = new LinkedHashMap<String, Object>(); change.put("before", from); change.put("after", to);
                diff.put(key, change);
            }
        }
        return diff;
    }
}
