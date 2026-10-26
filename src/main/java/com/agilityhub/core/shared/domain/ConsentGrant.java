package com.agilityhub.core.shared.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** A grant requires an explicit boolean. The canonical field takes precedence even when null or malformed. */
public final class ConsentGrant {
    private ConsentGrant() { }

    public static boolean granted(Map<?, ?> entry) {
        return entry != null && Boolean.TRUE.equals(entry.containsKey("granted") ? entry.get("granted") : entry.get("accepted"));
    }

    /** S03's legacy privacy acceptance is {acceptedAt, version}; unlike image rights it had no boolean field. */
    public static boolean legacyPrivacy(Map<?, ?> entry) {
        if (entry == null || entry.containsKey("granted") || entry.containsKey("accepted")) { return granted(entry); }
        return entry.get("acceptedAt") != null && entry.get("version") instanceof String version && !version.isBlank();
    }

    /** A read projection; the original ledger entry and its historical evidence remain unchanged. */
    public static Map<String, Object> image(Map<String, Object> entry) {
        var result = new LinkedHashMap<String, Object>(entry);
        result.put("granted", granted(entry));
        if (result.get("at") == null && entry.get("acceptedAt") != null) { result.put("at", entry.get("acceptedAt")); }
        return result;
    }
}
