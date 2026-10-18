package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.shared.application.lists.ListAccess;
import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ExportPolicy {
    public static final int SYNC_MAX_ROWS = 5000;
    public static final int MAX_ROWS = 100000;
    /** S14 R-14-13 / §9: a member's data package lives 7 days (`memberDataRetentionDays`); one per member every 24 hours. */
    public static final java.time.Duration MEMBER_DATA_RETENTION = java.time.Duration.ofDays(7), MEMBER_DATA_INTERVAL = java.time.Duration.ofHours(24);
    public static String contentType(String format) {
        if (format.equalsIgnoreCase("csv")) { return "text/csv;charset=UTF-8"; }
        return format.equalsIgnoreCase("pdf") ? "application/pdf" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }
    public void requireAllowed(String format) {
        requireAllowed(null, format);
    }
    public void requireAllowed(String listKey, String format) {
        ListAccess.account();
        if (!ListAccess.admin()) { throw new ApiException(ErrorCode.FORBIDDEN); }
        var formats = "accounting".equals(listKey) ? Set.of("xlsx", "csv") : Set.of("xlsx", "pdf");
        if (!formats.contains(format)) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
    }
    /** Defence in depth for future providers; renderers only receive this masked copy. */
    public Object mask(Object value) {
        if (value instanceof Map<?, ?> map) {
            var safe = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                String field = key.toString();
                if (field.equalsIgnoreCase("iban")) {
                    String text = Objects.toString(item, "");
                    safe.put("maskedAccount", text.isEmpty() ? "" : "···· ···· ···· ···· " + text.substring(Math.max(0, text.length() - 4)));
                } else if (field.equalsIgnoreCase("idDocument")) {
                    // R-03-27 (INC-38, E11-T10): the one format «38······1P»; a value the list projection already masked is kept.
                    String text = item instanceof Map<?, ?> document ? Objects.toString(document.get("number"), "") : Objects.toString(item, "");
                    safe.put(field, text.isEmpty() || text.contains("······") ? text : Masking.idDocument(text));
                } else if (field.equalsIgnoreCase("chip")) {
                    String text = item instanceof Map<?, ?> document ? Objects.toString(document.get("number"), "") : Objects.toString(item, "");
                    safe.put(field, text.isEmpty() ? "" : "···· " + text.substring(Math.max(0, text.length() - 3)));
                } else { safe.put(field, mask(item)); }
            });
            return safe;
        }
        if (value instanceof List<?> list) { return list.stream().map(this::mask).toList(); }
        return value;
    }
}
